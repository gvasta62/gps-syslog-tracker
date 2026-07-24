#!/usr/bin/with-contenv bashio
# Legge le opzioni dell'add-on e genera il config.ini del collector.
# Il sink e' Home Assistant tramite l'API interna del Supervisor: usa
# SUPERVISOR_TOKEN e http://supervisor/core (niente Cloudflare/token esterni).

ENTITY_PREFIX="$(bashio::config 'entity_prefix')"
ALLOWED="$(bashio::config 'allowed_hosts')"
FLUSH="$(bashio::config 'flush_timeout')"

cat > /app/config.ini <<EOF
[listen]
bind = 0.0.0.0
udp = true
tcp = true
udp_port = 5514
tcp_port = 5514

[collector]
flush_timeout = ${FLUSH}

[security]
allowed_hosts = ${ALLOWED}

[sink]
type = homeassistant

[homeassistant]
base_url = http://supervisor/core
token = ${SUPERVISOR_TOKEN}
entity_prefix = ${ENTITY_PREFIX}
EOF

bashio::log.info "GPS Syslog Collector: in ascolto su 5514 (UDP+TCP), sink=Home Assistant"
if [ -n "${ALLOWED}" ]; then
  bashio::log.info "Allowlist hostname: ${ALLOWED}"
fi

exec python3 /app/collector.py --config /app/config.ini
