#!/usr/bin/env bash
# IoT Devices — встановлення хаба на Raspberry Pi (Raspberry Pi OS / Debian).
#   curl -fsSL https://raw.githubusercontent.com/AlexOn1803/iot-devices/dev/hub/install.sh | sudo bash
# Повторний запуск оновлює хаб; налаштування (/etc/iot-hub) зберігаються.
set -euo pipefail

BRANCH="${IOTHUB_BRANCH:-dev}"
RAW="https://raw.githubusercontent.com/AlexOn1803/iot-devices/$BRANCH"
DIR=/opt/iot-hub
CONF=/etc/iot-hub/config.json
CF_API=https://api.cloudflare.com/client/v4

if [ "$(id -u)" -ne 0 ]; then echo "Запусти через sudo"; exit 1; fi
say() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }

say "Пакети системи"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv python3-pip avahi-daemon curl jq >/dev/null

say "Файли хаба"
id iothub >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin iothub
mkdir -p "$DIR/relay" /etc/iot-hub
curl -fsSL "$RAW/hub/iothub.py" -o "$DIR/iothub.py"
curl -fsSL "$RAW/relay/src/index.js" -o "$DIR/relay/index.js"
[ -d "$DIR/venv" ] || python3 -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q --upgrade pip
"$DIR/venv/bin/pip" install -q --upgrade aiohttp tinytuya
chown -R iothub:iothub "$DIR" /etc/iot-hub
chmod 700 /etc/iot-hub

say "Служба systemd"
cat > /etc/systemd/system/iot-hub.service <<EOF
[Unit]
Description=IoT Devices hub
After=network-online.target
Wants=network-online.target

[Service]
User=iothub
Environment=IOTHUB_CONFIG=$CONF
ExecStart=$DIR/venv/bin/python $DIR/iothub.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# щоб додаток знаходив хаб у домашній мережі
cat > /etc/avahi/services/iot-hub.service <<'EOF'
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">IoT hub %h</name>
  <service><type>_iothub._tcp</type><port>8380</port></service>
</service-group>
EOF
systemctl reload avahi-daemon 2>/dev/null || systemctl restart avahi-daemon || true

systemctl daemon-reload
systemctl enable -q iot-hub
systemctl restart iot-hub
# перший запуск створює токен
for _ in $(seq 1 20); do [ -s "$CONF" ] && break; sleep 0.5; done

RELAY=$(jq -r '.relay // ""' "$CONF" 2>/dev/null || echo "")
if [ -z "$RELAY" ] || [ "${IOTHUB_REDEPLOY:-}" = 1 ]; then
  say "Доступ поза домом (Cloudflare Worker, безкоштовно)"
  echo "Потрібен API-токен Cloudflare з правом «Edit Cloudflare Workers»:"
  echo "  dash.cloudflare.com → My Profile → API Tokens → Create Token → шаблон «Edit Cloudflare Workers»"
  echo "Токен використовується лише зараз і ніде не зберігається. Enter — пропустити (лише вдома)."
  read -r -s -p "API-токен: " CF_TOKEN < /dev/tty || CF_TOKEN=""
  echo
  if [ -n "$CF_TOKEN" ]; then
    H=(-H "Authorization: Bearer $CF_TOKEN")
    ACC=$(curl -fsS "${H[@]}" "$CF_API/accounts" | jq -r '.result[0].id')
    [ -n "$ACC" ] && [ "$ACC" != null ] || { echo "Не вдалося отримати акаунт — перевір токен"; exit 1; }
    META='{"main_module":"index.js","compatibility_date":"2025-09-01","bindings":[{"type":"durable_object_namespace","name":"ROOM","class_name":"Room"}]'
    if ! curl -fsS "${H[@]}" "$CF_API/accounts/$ACC/workers/scripts/iot-hub/settings" >/dev/null 2>&1; then
      META="$META,\"migrations\":{\"new_tag\":\"v1\",\"new_sqlite_classes\":[\"Room\"]}"
    fi
    META="$META}"
    curl -fsS -X PUT "${H[@]}" "$CF_API/accounts/$ACC/workers/scripts/iot-hub" \
      -F "metadata=$META;type=application/json" \
      -F "index.js=@$DIR/relay/index.js;type=application/javascript+module" | jq -e '.success' >/dev/null
    curl -fsS -X POST "${H[@]}" -H "Content-Type: application/json" \
      "$CF_API/accounts/$ACC/workers/scripts/iot-hub/subdomain" -d '{"enabled":true,"previews_enabled":false}' >/dev/null
    SUB=$(curl -fsS "${H[@]}" "$CF_API/accounts/$ACC/workers/subdomain" | jq -r '.result.subdomain')
    RELAY="https://iot-hub.$SUB.workers.dev"
    TMP=$(mktemp)
    jq --arg r "$RELAY" '.relay = $r' "$CONF" > "$TMP" && install -o iothub -g iothub -m 600 "$TMP" "$CONF" && rm -f "$TMP"
    systemctl restart iot-hub
    echo "Ретранслятор: $RELAY"
  fi
fi

IP=$(hostname -I | awk '{print $1}')
say "Готово"
echo "Хаб працює: http://$IP:8380/api/info"
echo "Поза домом: ${RELAY:-не налаштовано}"
echo
echo "Підключи додаток протягом 15 хвилин (телефон — у домашньому Wi-Fi):"
echo "  IoT Devices → Налаштування → Хаб Raspberry Pi → Знайти хаб"
echo "Якщо не знайде — введи адресу $IP"
echo "Відкрити підключення ще на 15 хв:  sudo systemctl kill -s USR1 iot-hub"
echo "Журнал хаба:                        journalctl -u iot-hub -f"
