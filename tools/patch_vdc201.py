from pathlib import Path
import re

p = Path('buildapp/lib/main.dart')
s = p.read_text()

s = s.replace(
    "  static const _testUrl = 'https://www.google.com/generate_204';",
    """  static const _testUrls = <String>[
    'https://www.google.com/generate_204',
    'https://cp.cloudflare.com/generate_204',
    'https://www.gstatic.com/generate_204',
    'https://example.com/',
  ];""",
)

# Add a deterministic, valid Remnawave-style HWID. It is used only by the
# real-test subscription fetch, never by the safe check.
needle = "  Future<_LoadedInput> _loadInput() async {"
replacement = r'''  String _testHwid(String seed) {
    var a = 0x811C9DC5;
    var b = 0x9E3779B9;
    for (final byte in utf8.encode(seed)) {
      a ^= byte;
      a = (a * 16777619) & 0xFFFFFFFF;
      b ^= (byte + 0x9D);
      b = (b * 2246822519) & 0xFFFFFFFF;
    }
    final ah = a.toRadixString(16).padLeft(8, '0');
    final bh = b.toRadixString(16).padLeft(8, '0');
    return 'VDC$ah$bh';
  }

  Future<_LoadedInput> _loadInput({bool happMode = false, String userAgent = 'Happ/3.15.2'}) async {'''
if needle not in s:
    raise SystemExit('loadInput signature not found')
s = s.replace(needle, replacement, 1)

old_headers = r'''      final response = await http.get(
        uri,
        headers: const {
          'User-Agent': 'VPNDeviceChecker/2.0 Android',
          'Accept': '*/*',
        },
      ).timeout(const Duration(seconds: 20));'''
new_headers = r'''      final headers = <String, String>{
        'User-Agent': happMode ? userAgent : 'VPNDeviceChecker/2.0 Android',
        'Accept': '*/*',
      };
      if (happMode) {
        headers.addAll({
          'x-hwid': _testHwid(raw),
          'x-device-os': 'Android',
          'x-ver-os': '16',
          'x-device-model': 'VPN Device Checker',
          'x-app-version': '2.0.2',
        });
      }
      final response = await http.get(uri, headers: headers).timeout(const Duration(seconds: 20));'''
if old_headers not in s:
    raise SystemExit('subscription http block not found')
s = s.replace(old_headers, new_headers, 1)

new_find = r'''  Future<int> _probeConfig(String config) async {
    for (final url in _testUrls) {
      try {
        final delay = await _vless.getServerDelay(config: config, url: url);
        if (delay >= 0) return delay;
      } catch (_) {}
    }
    return -1;
  }

  Future<_WorkingProfile> _findWorkingProfile(List<FlutterVlessURL> profiles) async {
    await _initFuture;
    final maxTry = math.min(profiles.length, 30);
    final failures = <String>[];
    for (var i = 0; i < maxTry; i++) {
      final profile = profiles[i];
      try {
        final config = profile.getFullConfiguration();
        final delay = await _probeConfig(config);
        if (delay >= 0) {
          return _WorkingProfile(profile: profile, config: config, baselineDelay: delay, index: i);
        }
        failures.add('#${i + 1}: все тестовые URL дали -1');
      } catch (e) {
        failures.add('#${i + 1}: ${_cleanError(e)}');
      }
    }
    throw Exception('Не удалось найти рабочий профиль среди $maxTry проверенных. ${failures.take(5).join(' | ')}');
  }

'''

s, n = re.subn(
    r"  Future<_WorkingProfile> _findWorkingProfile\(List<FlutterVlessURL> profiles\) async \{.*?\n  \}\n\n(?=  Future<List<_Probe>> _runRound)",
    new_find,
    s,
    flags=re.S,
)
if n != 1:
    raise SystemExit(f'findWorkingProfile replacement count={n}')

s = s.replace(
    "final delay = await _vless.getServerDelay(config: config, url: _testUrl);",
    "final delay = await _probeConfig(config);",
)

# In real test, try provider-specific subscription response variants. Safe check
# still calls _loadInput() with no HWID.
old_real = r'''      final loaded = await _loadInput();
      final profiles = _parseProfiles(loaded.payload);
      setState(() => _status = 'Ищу рабочий сервер из ${profiles.length} конфигураций…');
      final working = await _findWorkingProfile(profiles);'''
new_real = r'''      _LoadedInput? loaded;
      List<FlutterVlessURL>? profiles;
      _WorkingProfile? working;
      String selectedMode = '';
      final attempts = <String>[];
      final rawInput = _input.text.trim();

      if (rawInput.startsWith('http://') || rawInput.startsWith('https://')) {
        final variants = <Map<String, Object>>[
          {'name': 'Happ Android', 'ua': 'Happ/3.15.2', 'happ': true},
          {'name': 'v2RayTun', 'ua': 'v2RayTun/2.0', 'happ': true},
          {'name': 'Обычная подписка', 'ua': 'VPNDeviceChecker/2.0 Android', 'happ': false},
        ];
        for (final variant in variants) {
          try {
            final candidate = await _loadInput(
              happMode: variant['happ'] as bool,
              userAgent: variant['ua'] as String,
            );
            final candidateProfiles = _parseProfiles(candidate.payload);
            setState(() => _status = 'Проверяю ${variant['name']}: ${candidateProfiles.length} конфигураций…');
            final candidateWorking = await _findWorkingProfile(candidateProfiles);
            loaded = candidate;
            profiles = candidateProfiles;
            working = candidateWorking;
            selectedMode = variant['name'] as String;
            break;
          } catch (e) {
            attempts.add('${variant['name']}: ${_cleanError(e)}');
          }
        }
      } else {
        final candidate = await _loadInput();
        final candidateProfiles = _parseProfiles(candidate.payload);
        setState(() => _status = 'Ищу рабочий сервер из ${candidateProfiles.length} конфигураций…');
        working = await _findWorkingProfile(candidateProfiles);
        loaded = candidate;
        profiles = candidateProfiles;
        selectedMode = 'Отдельный ключ';
      }

      final selectedLoaded = loaded;
      final selectedProfiles = profiles;
      final selectedWorking = working;
      if (selectedLoaded == null || selectedProfiles == null || selectedWorking == null) {
        throw Exception('ASK-VPN отдал конфигурации, но ни один из режимов подписки не запустился. Это НЕ лимит устройств. ${attempts.take(3).join(' || ')}');
      }
      final loadedOk = selectedLoaded;
      final profilesOk = selectedProfiles;
      final workingOk = selectedWorking;'''
if old_real not in s:
    raise SystemExit('real-test load block not found')
s = s.replace(old_real, new_real, 1)

# Rename variables in the remainder of _realTest only.
start = s.index('      final loadedOk = selectedLoaded;')
end = s.index('    } catch (e) {', start)
chunk = s[start:end]
chunk = re.sub(r'\bworking\b', 'workingOk', chunk)
chunk = re.sub(r'\bloaded\b', 'loadedOk', chunk)
chunk = re.sub(r'\bprofiles\b', 'profilesOk', chunk)
# Repair declarations that were intentionally named workingOk/loadedOk/profilesOk already.
chunk = chunk.replace('final loadedOkOk = selectedLoaded;', 'final loadedOk = selectedLoaded;')
chunk = chunk.replace('final profilesOkOk = selectedProfiles;', 'final profilesOk = selectedProfiles;')
chunk = chunk.replace('final workingOkOk = selectedWorking;', 'final workingOk = selectedWorking;')
# Add selected response mode to output.
chunk = chunk.replace(
    "b.writeln('Профиль: ${title.isEmpty ? workingOk.profile.remark : title}');",
    "b.writeln('Профиль: ${title.isEmpty ? workingOk.profile.remark : title}');\n      b.writeln('Режим подписки: $selectedMode');\n      final hwidActive = loadedOk.headers['x-hwid-active'];\n      final hwidReached = loadedOk.headers['x-hwid-max-devices-reached'] ?? loadedOk.headers['x-hwid-limit'];\n      if (hwidActive != null) b.writeln('HWID-лимит сервера: $hwidActive');\n      if (hwidReached != null) b.writeln('HWID-максимум достигнут: $hwidReached');"
)
s = s[:start] + chunk + s[end:]

s = s.replace("VPN Device Checker 2'", "VPN Device Checker 2.0.2'")
p.write_text(s)

pub = Path('buildapp/pubspec.yaml')
ps = pub.read_text().replace('version: 2.0.0+2', 'version: 2.0.2+4')
pub.write_text(ps)
