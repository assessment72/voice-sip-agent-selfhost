import unittest
from unittest.mock import patch

import server


class TelegramAuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.original = set(server.ADMIN_IDS)
        server.ADMIN_IDS.clear()
        server.ADMIN_IDS.add(424242)

    def tearDown(self):
        server.ADMIN_IDS.clear()
        server.ADMIN_IDS.update(self.original)

    def test_private_message_from_allowed_admin(self):
        update = {
            "message": {
                "from": {"id": 424242},
                "chat": {"id": 424242},
                "text": "/devices",
            }
        }
        self.assertEqual(server.authorized_admin(update), 424242)

    def test_group_message_is_rejected(self):
        update = {
            "message": {
                "from": {"id": 424242},
                "chat": {"id": -100123},
                "text": "/devices",
            }
        }
        self.assertIsNone(server.authorized_admin(update))

    def test_unlisted_user_is_rejected(self):
        update = {
            "message": {
                "from": {"id": 777777},
                "chat": {"id": 777777},
                "text": "/devices",
            }
        }
        self.assertIsNone(server.authorized_admin(update))

    def test_private_callback_from_allowed_admin(self):
        update = {
            "callback_query": {
                "from": {"id": 424242},
                "message": {"chat": {"id": 424242}},
                "data": "status:phone-01",
            }
        }
        self.assertEqual(server.authorized_admin(update), 424242)

class MongoDBTelegramCommandTests(unittest.IsolatedAsyncioTestCase):
    async def test_database_status_reports_missing_configuration(self):
        with patch.object(server, "MONGODB_URI", ""), patch.object(server, "MONGODB_DB_NAME", ""):
            result = await server.database_status()
        self.assertIn("غير مهيأة", result)

    async def test_database_test_requires_explicit_connection(self):
        old_client, old_connected = server.mongo_client, server.mongo_connected
        try:
            server.mongo_client = None
            server.mongo_connected = False
            result = await server.database_test()
            self.assertIn("/db_connect", result)
        finally:
            server.mongo_client = old_client
            server.mongo_connected = old_connected


if __name__ == "__main__":
    unittest.main()
