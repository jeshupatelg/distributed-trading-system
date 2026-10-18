import unittest
from unittest.mock import patch, AsyncMock
import formatter

try:
    from fastapi.testclient import TestClient
    from main import app
    HAS_FASTAPI = True
except ImportError:
    HAS_FASTAPI = False


class TestAlertmanagerWebhook(unittest.TestCase):
    def setUp(self):
        if HAS_FASTAPI:
            self.client = TestClient(app)
        self.sample_payload_firing = {
            "version": "4",
            "groupKey": "test_group",
            "status": "firing",
            "receiver": "notification-service-webhook",
            "groupLabels": {
                "alertname": "ServiceInstanceDown",
                "severity": "critical",
                "tier": "infrastructure"
            },
            "commonLabels": {
                "alertname": "ServiceInstanceDown",
                "severity": "critical",
                "tier": "infrastructure"
            },
            "commonAnnotations": {
                "summary": "Service instance connection-manager-alpaca is DOWN",
                "description": "Target has been unreachable for > 30s."
            },
            "alerts": [
                {
                    "status": "firing",
                    "labels": {"instance": "connection-manager-alpaca:8000"},
                    "annotations": {"summary": "Service instance connection-manager-alpaca is DOWN"}
                }
            ]
        }

        self.sample_payload_resolved = {
            "version": "4",
            "status": "resolved",
            "commonLabels": {
                "alertname": "ServiceInstanceDown",
                "severity": "critical",
                "tier": "infrastructure"
            },
            "commonAnnotations": {
                "summary": "Service instance connection-manager-alpaca is DOWN",
                "description": "Target has been unreachable for > 30s."
            },
            "alerts": []
        }

    def test_format_alertmanager_telegram_firing(self):
        text = formatter.format_alertmanager_telegram(self.sample_payload_firing)
        self.assertIn("🚨 <b>RULE ALERT: [FIRING] ServiceInstanceDown</b>", text)
        self.assertIn("CRITICAL", text)
        self.assertIn("Target has been unreachable for > 30s.", text)

    def test_format_alertmanager_telegram_resolved(self):
        text = formatter.format_alertmanager_telegram(self.sample_payload_resolved)
        self.assertIn("✅ <b>RULE ALERT: [RESOLVED] ServiceInstanceDown</b>", text)

    def test_format_alertmanager_ntfy(self):
        title, body, priority, tags = formatter.format_alertmanager_ntfy(self.sample_payload_firing)
        self.assertIn("[FIRING]", title)
        self.assertEqual(priority, "urgent")
        self.assertIn("rotating_light", tags)

        title_res, body_res, prio_res, tags_res = formatter.format_alertmanager_ntfy(self.sample_payload_resolved)
        self.assertIn("[RESOLVED]", title_res)
        self.assertEqual(prio_res, "default")
        self.assertIn("white_check_mark", tags_res)

    def test_format_alertmanager_whatsapp(self):
        text = formatter.format_alertmanager_whatsapp(self.sample_payload_firing)
        self.assertIn("*🚨 RULE ALERT: [FIRING] ServiceInstanceDown*", text)
        self.assertIn("*Severity:* CRITICAL", text)

    @unittest.skipUnless(HAS_FASTAPI, "fastapi not installed in local environment")
    @patch("config.get_redis_client")
    @patch("config.get_active_config")
    @patch("main.send_telegram", new_callable=AsyncMock)
    @patch("main.send_ntfy", new_callable=AsyncMock)
    def test_webhook_endpoint_dispatch(self, mock_ntfy, mock_tg, mock_get_cfg, mock_get_redis):
        mock_get_redis.return_value = None
        mock_get_cfg.return_value = {
            "telegram_enabled": True,
            "telegram_token": "mock_token",
            "telegram_chat_id": "123456",
            "telegram_topic_id": "99",
            "ntfy_enabled": True,
            "ntfy_url": "https://ntfy.sh",
            "ntfy_topic": "test-alerts",
            "ntfy_token": None,
            "evolution_enabled": False
        }
        mock_tg.return_value = True
        mock_ntfy.return_value = True

        response = self.client.post("/api/v1/notify/alertmanager", json=self.sample_payload_firing)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "dispatched")
        self.assertEqual(data["alertname"], "ServiceInstanceDown")
        self.assertTrue(data["channel_results"]["telegram"]["success"])
        self.assertTrue(data["channel_results"]["ntfy"]["success"])

        mock_tg.assert_called_once()
        mock_ntfy.assert_called_once()


if __name__ == "__main__":
    unittest.main()
