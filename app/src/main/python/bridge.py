"""Point d'entrée Python appelé par Java (PythonHost). Toutes les réponses sont du JSON."""
import json
import sys

import agents
import brain
import store


def init(files_dir):
    store.init(files_dir)


def _dump(obj):
    return json.dumps(obj, ensure_ascii=False)


def handle(action, args_json):
    try:
        args = json.loads(args_json) if args_json else {}
        if action == "ping":
            return _dump({"pong": True, "python": sys.version.split()[0]})
        if action == "chat":
            return _dump(brain.chat(args))
        if action == "list_models":
            return _dump(brain.list_models(args))
        if action == "agents_get":
            return _dump({"agents": agents.get_config()})
        if action == "agents_autoconfigure":
            providers = json.loads(args.get("_providers") or "[]")
            return _dump({"agents": agents.autoconfigure(providers)})
        if action == "agents_save":
            return _dump({"agents": agents.save_config(args.get("agents"))})
        if action == "agent_chat":
            providers = json.loads(args.get("_providers") or "[]")
            opts = args.get("_mask") or {}
            return _dump(agents.chat(args, providers, opts))
        if action == "usage":
            return _dump(store.summary())
        if action == "memory_get":
            return _dump({"messages": store.get_messages(int(args.get("limit", 30)))})
        if action == "memory_clear":
            store.clear_messages()
            return _dump({"ok": True})
        return _dump({"error": "Action inconnue : %s" % action})
    except brain.BrainError as e:
        return _dump({"error": str(e)})
    except Exception as e:  # dernier filet : la page reçoit toujours une réponse lisible
        return _dump({"error": "%s : %s" % (type(e).__name__, e)})
