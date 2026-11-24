import unittest

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


if __name__ == "__main__":
    unittest.main()
