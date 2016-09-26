import unittest
import subprocess
from socket import socket, error as socket_error
import time
import fcp
# TODO: import configparser in Python 3 - conditional?
import ConfigParser

FCP_TIMEOUT = 10
FCP_POLL = 1

input_limit = "node.inputBandwidthLimit"
output_limit = "node.outputBandwidthLimit"

parser = ConfigParser.SafeConfigParser()
parser.read("config.ini")

# TODO: Move FreenetNode to common module


class FreenetNode:
    def __init__(self, path, port):
        self.path = "{}/run.sh ".format(path)
        subprocess.Popen(self.path + "start", shell=True)

        sock = socket()
        # TODO: datetime and timedelta instead?
        start = time.time()
        while time.time() - start < FCP_TIMEOUT:
            try:
                sock.connect(("127.0.0.1", port))
                sock.close()
                self.node = fcp.FCPNode(port=port)
                return
            except socket_error:
                pass

        print("Timed out waiting for FCP. Terminating node.")
        self.stop()
        exit(1)

    def stop(self):
        subprocess.Popen(self.path + "stop", shell=True).wait()


class BandwidthLimits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = FreenetNode(parser.get("config", 'path'),
                               parser.getint("config", "fcpport"))

    @classmethod
    def tearDownClass(cls):
        cls.node.node.shutdown()
        cls.node.stop()

    def test_low_limits(self):
        # Limit is in bytes; this is too low.
        low_limit = 100

        self.assert_unchanged(input_limit, low_limit)
        self.assert_unchanged(output_limit, low_limit)

    def test_reasonable_limits(self):
        kibibytes = 50
        reasonable_limit = "{}KiB".format(kibibytes)
        reasonable_limit_bytes = 1024 * kibibytes

        def check_restore(limit):
            existing = self.get(limit)
            self.set(limit, reasonable_limit)
            self.assertEqual(self.get(limit), reasonable_limit_bytes)
            self.set(limit, existing)

        check_restore(input_limit)
        check_restore(output_limit)

    def test_negative_sentinel(self):
        sentinel = -1
        # No meaning for upload
        self.assert_unchanged(output_limit, sentinel)
        self.assertNotEqual(self.get(output_limit), sentinel)

        self.set(input_limit, "-1")
        self.assertEqual(self.get(input_limit), sentinel)

    def assert_unchanged(self, limit, value):
        self.set(limit, value)
        self.assertNotEqual(self.get(limit), value)

    def get(self, key):
        config = self.node.node.getconfig(WithCurrent=True)
        return config["current.{}".format(key)]

    def set(self, key, value):
        args = {key: value}
        self.node.node.modifyconfig(**args)

if __name__ == '__main__':
    unittest.main()
