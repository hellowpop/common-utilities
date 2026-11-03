"""Linux integration tests using a real executable Java JAR (JDK required)."""
import os
from pathlib import Path
import shutil
import selectors
import subprocess
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parents[1]


class ControlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="ctl-build-")
        root = Path(cls.build.name)
        source = root / "Probe.java"
        source.write_text('''package com.inzent.apim.utilities;
public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("probe=" + System.getProperty("probe", "unset"));
        System.err.println("probe-stderr");
        for (String arg : args) System.out.println("arg=" + arg);
        if (args.length > 0 && args[0].equals("exit")) System.exit(7);
        if (args.length > 0 && args[0].equals("slow"))
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(10000); } catch (InterruptedException e) { }
            }));
        while (true) Thread.sleep(1000);
    }
}
''')
        subprocess.run(["javac", "-d", str(root), str(source)], check=True)
        cls.jar = root / "probe.jar"
        subprocess.run(["jar", "--create", "--file", str(cls.jar),
                        "--main-class", "com.inzent.apim.utilities.Probe",
                        "-C", str(root), "com"], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ctl app ")
        self.root = Path(self.temp.name)
        shutil.copy(SOURCE / "a.ctl", self.root)
        shutil.copy(self.jar, self.root / "app file.jar")
        self.config = self.root / "configure"
        self.config.write_text('APP_NAME=a\nJAR_FILE="app file.jar"\nMEMORY_OPTS=(-Xms16m -Xmx64m)\n'
                               'GC_OPTS=(-XX:+UseSerialGC)\nSTART_WAIT=1\nSTOP_TIMEOUT=2\n')

    def tearDown(self):
        # Restore usable stop settings even when the test corrupts the config.
        self.config.write_text('APP_NAME=a\nJAR_FILE="app file.jar"\n'
                               'STOP_TIMEOUT=1\nFORCE_STOP=true\n')
        self.run_ctl("stop")
        self.temp.cleanup()

    def run_ctl(self, *args, env=None):
        return subprocess.run(["bash", str(self.root / "a.ctl"), *args],
                              cwd="/", env=env, capture_output=True, text=True, timeout=20)

    def expect(self, code, *args, env=None):
        result = self.run_ctl(*args, env=env)
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        return result

    def append(self, text):
        with self.config.open("a") as file:
            file.write(text + "\n")

    def test_lifecycle_duplicate_start_and_typo_alias(self):
        self.expect(3, "status")
        self.expect(0, "start")
        before = (self.root / "run/a.pid").read_text()
        self.expect(0, "start")
        self.assertEqual(before, (self.root / "run/a.pid").read_text())
        self.expect(0, "staus")
        self.expect(0, "stop")
        self.expect(3, "status")
        self.expect(0, "stop")

    def test_options_and_application_arguments_preserve_spaces(self):
        self.append('AGENT_OPTS=("-Dprobe=agent value")\nAPP_ARGS=("hello world" "*.jar")')
        self.expect(0, "start")
        log = (self.root / "logs/a.log").read_text()
        self.assertIn("probe=agent value", log)
        self.assertIn("arg=hello world", log)
        self.assertIn("arg=*.jar", log)

    def test_bad_java_home_rejected_without_path_fallback(self):
        self.append('JAVA_HOME="/missing/jdk"')
        self.expect(1, "start")

    def test_java_home_and_path_detection(self):
        home = Path(shutil.which("java")).resolve().parents[1]
        self.append(f'JAVA_HOME="{home}"')
        self.expect(0, "start")
        self.expect(0, "status")
        self.expect(0, "stop")
        self.append('JAVA_HOME=""')
        env = dict(os.environ)
        env.pop("JAVA_HOME", None)
        self.expect(0, "start", env=env)
        self.expect(0, "status")

    def test_missing_jar_fails_without_pid(self):
        self.append('JAR_FILE="missing.jar"')
        self.expect(1, "start")
        self.assertFalse((self.root / "run/a.pid").exists())

    def test_early_exit_and_invalid_jvm_option_fail_start(self):
        self.append('APP_ARGS=(exit)')
        self.expect(1, "start")
        self.expect(3, "status")
        self.append('APP_ARGS=()\nJAVA_OPTS=(-XX:DoesNotExist)')
        self.expect(1, "start")

    def test_unrelated_pid_is_never_signalled(self):
        other = subprocess.Popen(["sleep", "30"])
        try:
            (self.root / "run").mkdir()
            boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
            ticks = Path(f"/proc/{other.pid}/stat").read_text().rsplit(") ", 1)[1].split()[19]
            for identity in (f"{other.pid} 0 {boot}", f"{other.pid} {ticks} stale-boot"):
                with self.subTest(identity=identity):
                    (self.root / "run/a.pid").write_text(identity + "\n")
                    self.expect(3, "status")
                    self.expect(0, "stop")
                    self.assertIsNone(other.poll())
            self.expect(0, "start")
        finally:
            other.terminate()
            other.wait()

    def test_invalid_pid_content_and_invalid_command(self):
        (self.root / "run").mkdir()
        (self.root / "run/a.pid").write_text("-1 invalid\n")
        self.expect(0, "stop")
        self.expect(2, "restart")
        self.expect(2)

    def test_timeout_retains_pid_then_explicit_force_stops(self):
        self.append('APP_ARGS=(slow)\nSTOP_TIMEOUT=1\nFORCE_STOP=false')
        self.expect(0, "start")
        self.expect(1, "stop")
        self.expect(0, "status")
        self.append('FORCE_STOP=true')
        self.expect(0, "stop")
        self.expect(3, "status")

    def test_concurrent_start_creates_one_process(self):
        command = ["bash", str(self.root / "a.ctl"), "start"]
        first = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        second = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, first.wait(timeout=15))
        self.assertEqual(0, second.wait(timeout=15))
        first.communicate()
        second.communicate()
        self.assertEqual(1, (self.root / "logs/a.log").read_text().count("probe="))
        self.expect(0, "stop")

    def test_invalid_timeout_rejected(self):
        self.append('STOP_TIMEOUT=-1')
        self.expect(1, "start")

    def test_fixed_configure_ignores_external_config_selection(self):
        external = self.root / "other.conf"
        external.write_text('JAVA_HOME="/missing/jdk"\n')
        env = dict(os.environ, CTL_CONFIG=str(external))
        self.expect(0, "start", env=env)
        self.expect(0, "status", env=env)
        self.expect(0, "stop", env=env)

    def test_missing_configure_does_not_fall_back_to_legacy_file(self):
        self.config.rename(self.root / "a.conf")
        result = self.expect(1, "start")
        self.assertIn(str(self.root / "configure"), result.stderr)

    def test_required_settings_cannot_be_missing_empty_or_inherited(self):
        env = dict(os.environ, APP_NAME="a", JAR_FILE="app file.jar")
        required = {"APP_NAME": 'APP_NAME=a\n', "JAR_FILE": 'JAR_FILE="app file.jar"\n'}
        for name in required:
            other_settings = "".join(line for key, line in required.items() if key != name)
            for assignment in ("", f'{name}=""\n', f'{name}="   "\n'):
                self.config.write_text(other_settings + assignment)
                for command in ("start", "stop", "status", "staus", "log"):
                    with self.subTest(name=name, assignment=assignment, command=command):
                        result = self.expect(1, command, env=env)
                        self.assertIn(name, result.stderr)
                        self.assertIn("configure", result.stderr)
                        self.assertFalse((self.root / "run").exists())

    def test_log_reports_missing_file_without_creating_it(self):
        result = self.expect(1, "log")
        self.assertIn(str(self.root / "logs/a.log"), result.stderr)
        self.assertFalse((self.root / "logs").exists())

    def test_log_tails_last_100_lines_and_follows_without_holding_lock(self):
        (self.root / "logs").mkdir()
        log = self.root / "logs/a.log"
        log.write_text("".join(f"history-{i}\n" for i in range(150)))
        self.expect(0, "start")
        viewer = subprocess.Popen(["bash", str(self.root / "a.ctl"), "log"], cwd="/",
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        output = b""

        def read_until(marker):
            nonlocal output
            with selectors.DefaultSelector() as selector:
                selector.register(viewer.stdout, selectors.EVENT_READ)
                deadline = time.monotonic() + 6
                while marker not in output and time.monotonic() < deadline:
                    for key, _ in selector.select(timeout=0.2):
                        chunk = os.read(key.fileobj.fileno(), 65536)
                        if not chunk:
                            self.fail(f"Log viewer exited: {output!r}")
                        output += chunk
            self.assertIn(marker, output)

        try:
            read_until(b"probe-stderr\n")
            self.assertEqual(100, len(output.splitlines()))
            self.assertTrue(output.startswith(b"history-52\n"), output)
            self.assertIn(b"probe=unset\n", output)
            with log.open("a") as file:
                file.write("followed-line\n")
            read_until(b"followed-line\n")
            self.assertIsNone(viewer.poll())
            self.expect(0, "status")
            self.expect(0, "stop")
            self.expect(3, "status")
            self.assertIsNone(viewer.poll())
        finally:
            viewer.terminate()
            viewer.communicate(timeout=5)

    def test_status_reports_live_process_details_and_untruncated_command(self):
        argument = "long-argument-" + "x" * 300 + "-end"
        self.append(f'APP_ARGS=("{argument}")')
        self.expect(0, "start")
        pid = (self.root / "run/a.pid").read_text().split()[0]
        # Status must describe the running JVM even after its launch config changes.
        self.append('JAR_FILE="missing.jar"\nJAVA_HOME="/missing/jdk"')
        for command in ("status", "staus"):
            with self.subTest(command=command):
                output = self.expect(0, command).stdout
                lines = output.splitlines()
                self.assertGreaterEqual(len(lines), 3, output)
                self.assertEqual(["PID", "PPID", "USER", "STAT", "STARTED", "ELAPSED",
                                  "CPU%", "MEM%", "RSS_KB", "COMMAND"], lines[1].split())
                self.assertRegex(lines[2], rf"^\s*{pid}\s+\d+\s+\S+\s+\S+\s+")
                self.assertIn("-Xms16m -Xmx64m -XX:+UseSerialGC -jar", lines[2])
                self.assertIn("app file.jar", lines[2])
                self.assertIn(argument, lines[2])
        self.expect(0, "stop")
        stopped = self.expect(3, "status").stdout
        self.assertNotIn("COMMAND", stopped)


if __name__ == "__main__":
    unittest.main(verbosity=2)
