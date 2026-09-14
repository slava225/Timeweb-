from pathlib import Path

p = Path('buildapp/lib/main.dart')
s = p.read_text()

old_sig = "  Future<_LoadedInput> _loadInput({bool happMode = false, String userAgent = 'Happ/3.15.2'}) async {"
new_sig = "  Future<_LoadedInput> _loadInput({bool happMode = false, String userAgent = 'Happ/1.0', String? sourceOverride}) async {"
if old_sig not in s:
    raise SystemExit('v2.0.2 loadInput signature not found')
s = s.replace(old_sig, new_sig, 1)

sig_pos = s.index(new_sig)
raw_needle = "    final raw = _input.text.trim();"
raw_pos = s.find(raw_needle, sig_pos)
if raw_pos < 0:
    raise SystemExit('loadInput raw input assignment not found')
s = s[:raw_pos] + "    final raw = sourceOverride ?? _input.text.trim();" + s[raw_pos + len(raw_needle):]

s = s.replace("'x-app-version': '2.0.2'", "'x-app-version': '2.0.3'")

marker = "  Future<int> _probeConfig(String config) async {"
if marker not in s:
    raise SystemExit('probe marker not found')
helpers = r'''  String _appendSubPath(String url, String suffix) {
    final uri = Uri.parse(url);
    var path = uri.path;
    if (path.endsWith('/')) path = path.substring(0, path.length - 1);
    return uri.replace(path: '$path$suffix').toString();
  }

  List<FlutterVlessURL> _parseProfilesUniversal(String payload) {
    try {
      final parsed = FlutterVless.parseMany(payload);
      if (parsed.isNotEmpty) return parsed;
    } catch (_) {}
    return _parseProfiles(payload);
  }

'''
s = s.replace(marker, helpers + marker, 1)

s = s.replace("final candidateProfiles = _parseProfiles(candidate.payload);", "final candidateProfiles = _parseProfilesUniversal(candidate.payload);")

old_variants = r'''        final variants = <Map<String, Object>>[
          {'name': 'Happ Android', 'ua': 'Happ/3.15.2', 'happ': true},
          {'name': 'v2RayTun', 'ua': 'v2RayTun/2.0', 'happ': true},
          {'name': 'Обычная подписка', 'ua': 'VPNDeviceChecker/2.0 Android', 'happ': false},
        ];'''
new_variants = r'''        final variants = <Map<String, Object>>[
          {'name': 'Xray JSON /json', 'ua': 'Happ/1.0', 'happ': true, 'suffix': '/json'},
          {'name': 'Xray JSON /v2ray-json', 'ua': 'Happ/1.0', 'happ': true, 'suffix': '/v2ray-json'},
          {'name': 'Happ Android', 'ua': 'Happ/1.0', 'happ': true, 'suffix': ''},
          {'name': 'v2RayTun', 'ua': 'v2RayTun/2.0', 'happ': true, 'suffix': ''},
          {'name': 'Обычная подписка', 'ua': 'VPNDeviceChecker/2.0 Android', 'happ': false, 'suffix': ''},
        ];'''
if old_variants not in s:
    raise SystemExit('v2.0.2 variants block not found')
s = s.replace(old_variants, new_variants, 1)

old_load = r'''            final candidate = await _loadInput(
              happMode: variant['happ'] as bool,
              userAgent: variant['ua'] as String,
            );'''
new_load = r'''            final suffix = variant['suffix'] as String;
            final source = suffix.isEmpty ? rawInput : _appendSubPath(rawInput, suffix);
            final candidate = await _loadInput(
              happMode: variant['happ'] as bool,
              userAgent: variant['ua'] as String,
              sourceOverride: source,
            );'''
if old_load not in s:
    raise SystemExit('v2.0.2 variant load block not found')
s = s.replace(old_load, new_load, 1)

s = s.replace("${attempts.take(3).join(' || ')}", "${attempts.take(5).join(' || ')}")
s = s.replace("VPN Device Checker 2.0.2'", "VPN Device Checker 2.0.3'")

p.write_text(s)

pub = Path('buildapp/pubspec.yaml')
ps = pub.read_text().replace('version: 2.0.2+4', 'version: 2.0.3+5')
pub.write_text(ps)
