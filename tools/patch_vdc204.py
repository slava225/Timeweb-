from pathlib import Path
import re

# ---------------- Dart app: preserve the actual probe error ----------------
p = Path('buildapp/lib/main.dart')
s = p.read_text()

probe_pattern = r'''  Future<int> _probeConfig\(String config\) async \{.*?\n  \}\n\n  Future<_WorkingProfile> _findWorkingProfile\(List<FlutterVlessURL> profiles\) async \{.*?\n  \}\n\n(?=  Future<List<_Probe>> _runRound)'''
probe_replacement = r'''  String _configKind(String config) {
    try {
      final root = jsonDecode(config);
      if (root is! Map) return 'unknown';
      final outbounds = root['outbounds'];
      if (outbounds is! List || outbounds.isEmpty || outbounds.first is! Map) return 'no-outbound';
      final outbound = outbounds.first as Map;
      final parts = <String>[];
      final protocol = outbound['protocol']?.toString();
      if (protocol != null && protocol.isNotEmpty) parts.add(protocol);
      final stream = outbound['streamSettings'];
      if (stream is Map) {
        final network = stream['network']?.toString();
        final security = stream['security']?.toString();
        if (network != null && network.isNotEmpty) parts.add(network);
        if (security != null && security.isNotEmpty && security != 'none') parts.add(security);
      }
      final settings = outbound['settings'];
      if (settings is Map) {
        final vnext = settings['vnext'];
        if (vnext is List && vnext.isNotEmpty && vnext.first is Map) {
          final users = (vnext.first as Map)['users'];
          if (users is List && users.isNotEmpty && users.first is Map) {
            final flow = (users.first as Map)['flow']?.toString();
            final encryption = (users.first as Map)['encryption']?.toString();
            if (flow != null && flow.isNotEmpty) parts.add(flow);
            if (encryption != null && encryption.isNotEmpty && encryption != 'none') {
              parts.add('enc');
            }
          }
        }
      }
      return parts.isEmpty ? 'unknown' : parts.join('/');
    } catch (_) {
      return 'unknown';
    }
  }

  Future<int> _probeConfig(String config) async {
    final errors = <String>[];
    for (final url in _testUrls) {
      try {
        final delay = await _vless.getServerDelay(config: config, url: url);
        if (delay >= 0) return delay;
        if (!errors.contains('Xray вернул -1 без диагностики')) {
          errors.add('Xray вернул -1 без диагностики');
        }
      } catch (e) {
        final message = _cleanError(e).replaceAll('\n', ' ').trim();
        if (message.isNotEmpty && !errors.contains(message)) errors.add(message);
        // A native/config startup failure is independent of the HTTP test URL.
        final lower = message.toLowerCase();
        if (lower.contains('xray заверш') ||
            lower.contains('ошибка запуска xray') ||
            lower.contains('failed to load') ||
            lower.contains('failed to start') ||
            lower.contains('config')) {
          break;
        }
      }
    }
    throw Exception(errors.isEmpty
        ? 'Xray не сообщил причину ошибки'
        : errors.take(2).join(' ; '));
  }

  Future<_WorkingProfile> _findWorkingProfile(List<FlutterVlessURL> profiles) async {
    await _initFuture;
    final maxTry = math.min(profiles.length, 30);
    final failures = <String>[];
    for (var i = 0; i < maxTry; i++) {
      var kind = 'unknown';
      try {
        final profile = profiles[i];
        final config = profile.getFullConfiguration();
        kind = _configKind(config);
        final delay = await _probeConfig(config);
        if (delay >= 0) {
          return _WorkingProfile(profile: profile, config: config, baselineDelay: delay, index: i);
        }
        failures.add('#${i + 1} [$kind]: Xray вернул $delay');
      } catch (e) {
        failures.add('#${i + 1} [$kind]: ${_cleanError(e)}');
      }
    }
    throw Exception('Не удалось найти рабочий профиль среди $maxTry проверенных. ${failures.take(5).join(' | ')}');
  }

'''

s, n = re.subn(probe_pattern, lambda _m: probe_replacement, s, flags=re.S)
if n != 1:
    raise SystemExit(f'probe/find replacement count={n}')

s = s.replace("VPN Device Checker 2.0.3'", "VPN Device Checker 2.0.4'")
p.write_text(s)

pub = Path('buildapp/pubspec.yaml')
ps = pub.read_text().replace('version: 2.0.3+5', 'version: 2.0.4+6')
pub.write_text(ps)

# ---------------- Android native runtime: capture Xray output ----------------
core = Path('buildapp/vendor/flutter_vless/packages/flutter_vless_android/android/src/main/kotlin/com/github/tfox/flutter_vless/xray/core/XrayCoreManager.kt')
cs = core.read_text()

state_needle = '    private var lastProxyDownlink = 0L\n'
if state_needle not in cs:
    raise SystemExit('XrayCoreManager state marker not found')
cs = cs.replace(state_needle, state_needle + '''    @Volatile private var lastProbeDiagnostic = "Проверка Xray ещё не запускалась"

    fun getLastProbeDiagnostic(): String = lastProbeDiagnostic

    private fun sanitizeProbeDiagnostic(raw: String): String {
        return raw
            .replace(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<uuid>")
            .replace(Regex("(?i)vless://\\S+"), "vless://<hidden>")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1800)
    }

''', 1)

func_pattern = r'''    fun getServerDelay\(context: Context, configJson: String, url: String\): Long \{.*?\n    \}\n\n    private fun drain'''
func_replacement = r'''    fun getServerDelay(context: Context, configJson: String, url: String): Long {
        var process: Process? = null
        var outputThread: Thread? = null
        val file = File(context.noBackupFilesDir, "delay-${UUID.randomUUID()}.json")
        return try {
            lastProbeDiagnostic = "Запуск Xray…"
            val port = ServerSocket(0).use { it.localPort }
            val credentials = LocalProxyCredentials.generate()
            val (json, socksPort) = buildDelayConfigJson(configJson, port, context.noBackupFilesDir, credentials)
            // The upstream delay helper disables all logs, which makes every failure look like -1.
            // Warning level is used only for this local diagnostic probe.
            json.put("log", JSONObject().put("loglevel", "warning"))
            file.writeText(json.toString())
            Utilities.copyAssets(context)
            val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath,
                "run", "-config", file.absolutePath).directory(context.noBackupFilesDir).redirectErrorStream(true)
            builder.environment()["XRAY_LOCATION_ASSET"] = Utilities.getUserAssetsPath(context)
            val startedProcess = builder.start()
            process = startedProcess
            val captured = StringBuilder()
            outputThread = Thread({
                runCatching {
                    startedProcess.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(captured) {
                                if (captured.length < 12000) captured.append(line).append('\n')
                            }
                        }
                    }
                }
            }, "xray-probe-output").apply { isDaemon = true; start() }

            var lastMeasureError = "SOCKS ещё не ответил"
            repeat(12) {
                if (!startedProcess.isAlive) {
                    outputThread?.join(300)
                    val code = runCatching { startedProcess.exitValue() }.getOrDefault(-999)
                    val native = synchronized(captured) { captured.toString() }
                    lastProbeDiagnostic = sanitizeProbeDiagnostic(
                        "Xray завершился сразу (код $code). ${native.ifBlank { "Нативный вывод пуст." }}"
                    )
                    return -1
                }
                try {
                    val measured = AuthenticatedSocksClient.measure(socksPort, credentials, url)
                    lastProbeDiagnostic = "OK: Xray и SOCKS работают"
                    return measured
                } catch (error: Exception) {
                    lastMeasureError = "${error.javaClass.simpleName}: ${error.message ?: "без сообщения"}"
                    Thread.sleep(150)
                }
            }
            val native = synchronized(captured) { captured.toString() }
            lastProbeDiagnostic = sanitizeProbeDiagnostic(
                "Xray запущен, но тестовый запрос не прошёл: $lastMeasureError. ${native.ifBlank { "Нативный вывод пуст." }}"
            )
            -1
        } catch (error: Exception) {
            lastProbeDiagnostic = sanitizeProbeDiagnostic(
                "Ошибка запуска Xray: ${error.javaClass.simpleName}: ${error.message ?: "без сообщения"}"
            )
            -1
        } finally {
            process?.destroy()
            if (process?.waitFor(1, TimeUnit.SECONDS) == false) process?.destroyForcibly()
            outputThread?.join(200)
            file.delete()
        }
    }

    private fun drain'''

cs, n = re.subn(func_pattern, lambda _m: func_replacement, cs, flags=re.S)
if n != 1:
    raise SystemExit(f'getServerDelay replacement count={n}')
core.write_text(cs)

# Return the diagnostic to Dart instead of silently returning -1.
plugin = Path('buildapp/vendor/flutter_vless/packages/flutter_vless_android/android/src/main/kotlin/com/github/tfox/flutter_vless/FlutterVlessPlugin.kt')
ks = plugin.read_text()
old = '''                    val delay = XrayCoreManager.getServerDelay(currentActivity, configJson, url)
                    currentActivity.runOnUiThread {
                        result.success(delay)
                    }'''
new = '''                    val delay = XrayCoreManager.getServerDelay(currentActivity, configJson, url)
                    currentActivity.runOnUiThread {
                        if (delay >= 0) {
                            result.success(delay)
                        } else {
                            result.error("XRAY_PROBE_FAILED", XrayCoreManager.getLastProbeDiagnostic(), null)
                        }
                    }'''
if old not in ks:
    raise SystemExit('FlutterVlessPlugin getServerDelay block not found')
ks = ks.replace(old, new, 1)
plugin.write_text(ks)