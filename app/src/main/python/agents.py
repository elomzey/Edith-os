"""Agence d'IA : un rôle = une tâche précise, avec son propre modèle et son propre prompt.

Le patron (« boss ») gère la conversation générale ; les autres rôles ne servent
que si on les sollicite explicitement (le routage automatique des tâches viendra
dans une phase ultérieure). La configuration automatique se base sur les capacités
devinées des modèles (voir brain._guess_caps) : c'est une estimation, à corriger
si besoin depuis l'onglet Agence.
"""
import json

import brain
import store

ROLES = [
    {"id": "boss", "label": "Patron (conversation générale)", "cap": "chat",
     "prompt": brain.SYSTEM_PROMPT},
    {"id": "vision", "label": "Analyste d'image", "cap": "vision",
     "prompt": ("Tu analyses des images ou des captures d'écran pour l'utilisateur. "
                "Décris ce qui est pertinent, en français, sans rien inventer de ce que tu ne vois pas.")},
    {"id": "image_gen", "label": "Génération d'images", "cap": "image_gen",
     "prompt": ("Tu transformes une demande en une description d'image précise et détaillée, "
                "en anglais, prête à être utilisée par un générateur d'images.")},
    {"id": "tts", "label": "Voix (synthèse)", "cap": "tts", "prompt": ""},
    {"id": "stt", "label": "Transcription audio", "cap": "stt", "prompt": ""},
]
_ROLE_IDS = {r["id"] for r in ROLES}
_BY_ID = {r["id"]: r for r in ROLES}


def get_config():
    raw = store.get_kv("agents_config")
    cfg = json.loads(raw) if raw else {}
    out = []
    for r in ROLES:
        c = cfg.get(r["id"], {})
        out.append({
            "id": r["id"],
            "label": r["label"],
            "cap": r["cap"],
            "provider_id": c.get("provider_id", ""),
            "model_id": c.get("model_id", ""),
            "enabled": c.get("enabled", True),
        })
    return out


def save_config(entries):
    cfg = {}
    for e in entries or []:
        rid = e.get("id")
        if rid not in _ROLE_IDS:
            continue
        cfg[rid] = {
            "provider_id": e.get("provider_id", ""),
            "model_id": e.get("model_id", ""),
            "enabled": bool(e.get("enabled", True)),
        }
    store.set_kv("agents_config", json.dumps(cfg))
    return get_config()


def autoconfigure(providers):
    """Choisit le meilleur modèle disponible pour chaque rôle, sans écraser un choix déjà fait."""
    current = {c["id"]: c for c in get_config()}
    catalogue = []  # (id_api, id_modèle, capacités, titulaire/secours)
    for p in providers or []:
        if not p.get("enabled", True):
            continue
        for m in p.get("models", []):
            if m.get("enabled", True):
                catalogue.append((p["id"], m["id"], m.get("caps") or [], m.get("tier", "main")))

    def pick(cap):
        mains = [c for c in catalogue if cap in c[2] and c[3] == "main"]
        backups = [c for c in catalogue if cap in c[2] and c[3] == "backup"]
        chosen = mains or backups
        return chosen[0] if chosen else None

    entries = []
    for r in ROLES:
        cur = current.get(r["id"])
        if cur and cur.get("provider_id") and cur.get("model_id"):
            entries.append(cur)
            continue
        found = pick(r["cap"])
        entries.append({
            "id": r["id"],
            "provider_id": found[0] if found else "",
            "model_id": found[1] if found else "",
            "enabled": found is not None,
        })
    return save_config(entries)


def chat(args, providers, mask_opts):
    role_id = args.get("role") or "boss"
    role_def = _BY_ID.get(role_id)
    if role_def is None:
        raise brain.BrainError("Rôle inconnu : %s" % role_id)

    cfg = {c["id"]: c for c in get_config()}
    c = cfg.get(role_id, {})
    if not c.get("enabled") or not c.get("model_id"):
        raise brain.BrainError(
            "Aucun modèle assigné au rôle « %s ». Configure l'agence (menu ☰ > Agence)." % role_def["label"])

    messages = args.get("messages") or []
    if not messages:
        raise brain.BrainError("Aucun message à envoyer")

    msgs = [{"role": "system", "content": role_def["prompt"] or brain.SYSTEM_PROMPT}]
    for m in messages:
        msgs.append({"role": m["role"], "content": brain._mask(str(m["content"]), mask_opts)})

    result = brain._dispatch(msgs, c["model_id"], providers)
    result["role"] = role_id

    if args.get("remember", True):
        store.add_message("user", str(messages[-1]["content"]))
        store.add_message("assistant", result["text"])
    return result
