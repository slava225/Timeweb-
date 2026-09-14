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
    throw Exception('Не удалось найти рабочий профиль среди $maxTry проверенных. Это НЕ означает лимит устройств. ${failures.take(5).join(' | ')}');
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

s = s.replace("VPN Device Checker 2'", "VPN Device Checker 2.0.1'")
p.write_text(s)

pub = Path('buildapp/pubspec.yaml')
ps = pub.read_text().replace('version: 2.0.0+2', 'version: 2.0.1+3')
pub.write_text(ps)
