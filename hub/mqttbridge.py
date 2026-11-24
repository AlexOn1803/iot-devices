"""Місток до локального MQTT-брокера на Pi (Mosquitto): плати ESP публікують туди свій стан миттєво.
Топіки ті самі, що й у прошивці: iotdev/<id>/state, /status, /info; команди — iotdev/<id>/set."""
import json
import logging

log = logging.getLogger("iothub.mqtt")


class MqttBridge:
    def __init__(self, hub, cfg: dict):
        self.hub = hub
        self.cfg = cfg
        self.connected = False
        self.client = None

    def start(self):
        import paho.mqtt.client as mqtt
        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="iot-hub")
        if self.cfg.get("user"):
            c.username_pw_set(self.cfg["user"], self.cfg.get("pass", ""))
        c.on_connect = self._on_connect
        c.on_disconnect = self._on_disconnect
        c.on_message = self._on_message
        c.reconnect_delay_set(1, 30)
        c.connect_async(self.cfg.get("host", "127.0.0.1"), int(self.cfg.get("port", 1883)), keepalive=30)
        c.loop_start()
        self.client = c

    def _on_connect(self, c, userdata, flags, reason, props=None):
        self.connected = not getattr(reason, "is_failure", False)
        if self.connected:
            c.subscribe([("iotdev/+/state", 0), ("iotdev/+/status", 0)])
            log.info("mqtt connected")
        else:
            log.warning("mqtt connect failed: %s", reason)

    def _on_disconnect(self, c, userdata, flags, reason, props=None):
        self.connected = False

    def _on_message(self, c, userdata, msg):
        parts = msg.topic.split("/")
        if len(parts) != 3:
            return
        dev_id, kind = parts[1], parts[2]
        payload = msg.payload.decode("utf-8", "replace")
        self.hub.loop.call_soon_threadsafe(self._dispatch, dev_id, kind, payload)

    def _dispatch(self, dev_id, kind, payload):
        key = next((d["key"] for d in self.hub.cfg.data.get("devices", [])
                    if d.get("kind") == "ESP" and d.get("deviceId") == dev_id), None)
        w = self.hub.esp.get(key) if key else None
        if not w:
            return
        if kind == "state":
            try:
                w.on_mqtt_state(json.loads(payload))
            except ValueError:
                pass
        elif kind == "status" and payload == "offline":
            w.on_mqtt_offline()

    def publish(self, topic, obj):
        if self.client is not None:
            self.client.publish(topic, json.dumps(obj), qos=1)
