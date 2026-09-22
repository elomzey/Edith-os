"""Cerveau cloud : APIs compatibles OpenAI, bascule automatique et suivi des tokens.

- Un même modèle proposé par plusieurs APIs forme un « pool » : si une API est épuisée
  (429, quota, panne), la suivante prend le relais sans bruit.
- Les modèles « secours » ne servent que si tous les titulaires sont indisponibles.
- Aucune clé n'est stockée ici : Java les transmet à chaque appel.
"""
import json
import re
import threading
import time
import urllib.error
import urllib.request

import store


class BrainError(Exception):
    pass


SYSTEM_PROMPT = (
    "Tu es EDITH, l'assistante personnelle intégrée au téléphone de l'utilisateur. "
    "Réponds en français, de façon claire et concise. "
    "Indique ton niveau de certitude (sûr, probable ou incertain) quand une information peut être fausse. "
    "Ne prétends jamais pouvoir faire ce que tu ne sais pas faire : "
    "si une capacité manque, dis-le et propose comment l'obtenir."
)

_cooldown = {}  # (id_api, modèle) -> instant jusqu'auquel on évite cette API
_lock = threading.Lock()

_CARD = re.compile(r"\b\d(?:[ -]?\d){12,18}\b")
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_PHONE = re.compile(r"(?<!\d)\+?\d[\d .-]{7,}\d(?!\d)")


def _mask(text, opts):
    if opts.get("cards", True):
        text = _CARD.sub("[CARTE MASQUÉE]", text)
    if opts.get("emails"):
        text = _EMAIL.sub("[E-MAIL MASQUÉ]", text)
    if opts.get("phones"):
        text = _PHONE.sub("[TÉLÉPHONE MASQUÉ]", text)
    return text


def _parse_duration(s):
    total, found = 0.0, False
    for num, unit in re.findall(r"([\d.]+)\s*(ms|h|m|s)", s):
        found = True
        total += float(num) * {"h": 3600, "m": 60, "s": 1, "ms": 0.001}[unit]
    return int(total) + 1 if found else None


def _retry_after(headers):
    if headers is None:
        return None
    ra = headers.get("Retry-After")
    if ra:
        try:
            return min(int(float(ra)), 3600)
        except ValueError:
            pass
    for key in ("x-ratelimit-reset-tokens", "x-ratelimit-reset-requests"):
        v = headers.get(key)
        if v:
            secs = _parse_duration(v)
            if secs:
                return min(secs, 3600)
    return None


def _body(err):
    try:
        return err.read().decode("utf-8", "replace")[:300]
    except Exception:
        return ""


def _request(provider, path, payload=None, timeout=60):
    url = provider["base_url"].rstrip("/") + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if payload is not None else "GET")
    req.add_header("Authorization", "Bearer " + provider["api_key"])
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "EdithOS/0.1")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", "replace")
        headers = {k.lower(): v for k, v in resp.headers.items()}
    return json.loads(body), headers


def _pause(key, seconds, reason):
    with _lock:
        _cooldown[key] = time.time() + seconds
    store.log_event("pause", "%s — pause %d s" % (reason, seconds))


def _ready(pm, now):
    return _cooldown.get((pm[0]["id"], pm[1]["id"]), 0) <= now


def _candidates(providers, wanted):
    mains, backups = [], []
    for p in providers:
        if not p.get("enabled", True) or not p.get("api_key") or not p.get("base_url"):
            continue
        for m in p.get("models", []):
            if not m.get("enabled", True):
                continue
            if m.get("tier", "main") == "backup":
                backups.append((p, m))
            elif not wanted or m["id"] == wanted:
                mains.append((p, m))
    return mains, backups


def _dispatch(msgs, wanted, providers):
    """Choisit une API disponible pour ce modèle (ou pool de modèles), avec bascule et journal."""
    mains, backups = _candidates(providers, wanted)
    if not mains and not backups:
        raise BrainError("Aucune API configurée pour cette tâche : ajoute une API et un modèle.")

    now = time.time()
    order = [pm for pm in mains if _ready(pm, now)] + [pm for pm in backups if _ready(pm, now)]
    if not order:
        soonest = min(_cooldown.get((p["id"], m["id"]), now) for p, m in mains + backups) - now
        raise BrainError("Toutes les API sont en pause (quota ou erreur). Reprise dans environ %d s." % max(1, soonest))

    errors = []
    for idx, (p, m) in enumerate(order):
        key = (p["id"], m["id"])
        label = "%s/%s" % (p.get("name", p["id"]), m["id"])
        try:
            data, headers = _request(
                p, "/chat/completions",
                {"model": m["id"], "messages": msgs, "temperature": 0.7})
            text = data["choices"][0]["message"]["content"] or ""
        except urllib.error.HTTPError as e:
            body = _body(e)
            wait = _retry_after(e.headers)
            if e.code in (401, 403):
                wait = 3600
            elif e.code in (429, 402) or e.code >= 500 or "quota" in body.lower():
                wait = wait or 60
            else:
                wait = wait or 20
            _pause(key, wait, "%s : HTTP %d" % (label, e.code))
            errors.append("%s : HTTP %d" % (label, e.code))
            continue
        except (urllib.error.URLError, OSError, ValueError, KeyError, IndexError, TypeError) as e:
            _pause(key, 30, "%s : %s" % (label, type(e).__name__))
            errors.append("%s : %s" % (label, type(e).__name__))
            continue

        usage = data.get("usage") or {}
        store.record_usage(p["id"], m["id"], usage.get("prompt_tokens"), usage.get("completion_tokens"))
        store.record_quota(p["id"], m["id"], headers)

        target = wanted or order[0][1]["id"]
        tier = m.get("tier", "main")
        degraded = tier == "backup" or m["id"] != target
        if idx > 0:
            store.log_event("bascule", "%s → %s" % (
                "%s/%s" % (order[0][0].get("name"), order[0][1]["id"]), label))

        return {
            "text": text,
            "provider": p.get("name", p["id"]),
            "model": m["id"],
            "tier": tier,
            "degraded": degraded,
            "tokens": {"prompt": usage.get("prompt_tokens", 0), "completion": usage.get("completion_tokens", 0)},
        }

    raise BrainError("Aucune API n'a pu répondre : " + " ; ".join(errors))


def chat(args):
    providers = json.loads(args.get("_providers") or "[]")
    opts = args.get("_mask") or {}
    messages = args.get("messages") or []
    if not messages:
        raise BrainError("Aucun message à envoyer")
    wanted = args.get("model") or ""

    msgs = [{"role": "system", "content": SYSTEM_PROMPT}]
    for m in messages:
        msgs.append({"role": m["role"], "content": _mask(str(m["content"]), opts)})

    result = _dispatch(msgs, wanted, providers)

    if args.get("remember", True):
        store.add_message("user", str(messages[-1]["content"]))
        store.add_message("assistant", result["text"])
    return result


def _guess_caps(model_id):
    """Estimation à partir du nom : à vérifier par l'utilisateur."""
    m = model_id.lower()
    if any(k in m for k in ("whisper", "transcri", "stt")):
        return ["stt"]
    if any(k in m for k in ("tts", "speech")):
        return ["tts"]
    if "embed" in m:
        return ["embedding"]
    if any(k in m for k in ("dall", "flux", "sdxl", "stable-diffusion", "imagen", "image")):
        return ["image_gen"]
    caps = ["chat"]
    if any(k in m for k in ("vision", "-vl", "vl-", "llava", "4o", "gemini", "pixtral", "llama-4", "claude")):
        caps.append("vision")
    return caps


def list_models(args):
    providers = json.loads(args.get("_providers") or "[]")
    p = next((x for x in providers if x.get("id") == args.get("provider_id")), None)
    if p is None:
        raise BrainError("API inconnue : enregistre-la d'abord")
    if not p.get("api_key") or not p.get("base_url"):
        raise BrainError("URL ou clé manquante")
    try:
        data, _ = _request(p, "/models", None, timeout=30)
    except urllib.error.HTTPError as e:
        raise BrainError("HTTP %d : %s" % (e.code, _body(e)))
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise BrainError("Connexion impossible : %s" % e)
    items = data.get("data", []) if isinstance(data, dict) else data
    models = []
    for it in items or []:
        mid = it.get("id") if isinstance(it, dict) else str(it)
        if mid:
            models.append({"id": mid, "caps": _guess_caps(mid)})
    models.sort(key=lambda x: x["id"])
    return {"models": models}
