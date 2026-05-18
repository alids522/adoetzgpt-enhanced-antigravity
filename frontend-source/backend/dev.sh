export CORS_ALLOW_ORIGIN="*"
export WEBUI_URL="https://chat2.alids.app"
PORT="${PORT:-8080}"
uvicorn open_webui.main:app --port $PORT --host 0.0.0.0 --forwarded-allow-ips "${FORWARDED_ALLOW_IPS:-*}" --reload
