import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:logging/logging.dart';
import 'package:path/path.dart' as p;

import 'error.dart';

final _log = Logger('util');

const _sensitiveBuildKeys = {
  'DNS_AUTH_SECRET',
  'DNS_AUTH_DOMAINS',
};

final _sensitiveEnvPattern = RegExp(
  '(${_sensitiveBuildKeys.map(RegExp.escape).join('|')})=([^\\s,}]+)',
);

final _goLinkerSecretPattern = RegExp(
  r'(-X\s+github\.com/metacubex/mihomo/component/dnsauth\.GlobalDNSAuth(?:Secret|Domains)=)\S+',
);

String _redactSensitive(String value) {
  return value
      .replaceAllMapped(
        _sensitiveEnvPattern,
        (match) => '${match[1]}=<redacted>',
      )
      .replaceAllMapped(
        _goLinkerSecretPattern,
        (match) => '${match[1]}<redacted>',
      );
}

String _redactArguments(List<String> arguments) {
  return arguments.map(_redactSensitive).join(' ');
}

Map<String, String> _redactEnvironment(Map<String, String> environment) {
  return environment.map((key, value) {
    return MapEntry(
      key,
      _sensitiveBuildKeys.contains(key)
          ? '<redacted>'
          : _redactSensitive(value),
    );
  });
}

ProcessResult runCommand(
  String executable,
  List<String> arguments, {
  String? workingDirectory,
  Map<String, String>? environment,
  bool includeParentEnvironment = true,
}) {
  _log.finer('Running: $executable ${_redactArguments(arguments)}');
  if (environment != null && environment.isNotEmpty) {
    _log.finer('  env: ${_redactEnvironment(environment)}');
  }
  final result = Process.runSync(
    executable,
    arguments,
    workingDirectory: workingDirectory,
    environment: environment,
    includeParentEnvironment: includeParentEnvironment,
    stdoutEncoding: systemEncoding,
    stderrEncoding: systemEncoding,
  );
  final out = (result.stdout as String).trim();
  final err = (result.stderr as String).trim();
  if (out.isNotEmpty) _log.finest(_redactSensitive(out));
  if (err.isNotEmpty) _log.finest(_redactSensitive(err));
  if (result.exitCode != 0) {
    throw CommandFailedException(
      executable: executable,
      arguments: arguments.map(_redactSensitive).toList(),
      exitCode: result.exitCode,
      stdout: _redactSensitive(out),
      stderr: _redactSensitive(err),
    );
  }
  return result;
}

Future<void> runCommandStream(
  String executable,
  List<String> arguments, {
  String? workingDirectory,
  Map<String, String>? environment,
}) async {
  _log.info('exec: $executable ${_redactArguments(arguments)}');
  final process = await Process.start(
    executable,
    arguments,
    workingDirectory: workingDirectory,
    environment: environment,
    includeParentEnvironment: true,
    runInShell: Platform.isWindows,
  );
  process.stdout.transform(utf8.decoder).listen((data) {
    for (final line in data.split('\n')) {
      if (line.isNotEmpty) _log.info(_redactSensitive(line));
    }
  });
  process.stderr.transform(utf8.decoder).listen((data) {
    for (final line in data.split('\n')) {
      if (line.isNotEmpty) _log.warning(_redactSensitive(line));
    }
  });
  final exitCode = await process.exitCode;
  if (exitCode != 0) {
    throw CommandFailedException(
      executable: executable,
      arguments: arguments.map(_redactSensitive).toList(),
      exitCode: exitCode,
      stdout: '',
      stderr: '',
    );
  }
}

Future<String> calcSha256(String filePath) async {
  final file = File(filePath);
  if (!await file.exists()) {
    throw BuildException('File not found: $filePath');
  }
  final hash = await sha256.bind(file.openRead()).first;
  return hash.toString();
}

void ensureDir(String dirPath) {
  final dir = Directory(dirPath);
  if (!dir.existsSync()) {
    dir.createSync(recursive: true);
  }
}

void copyFile(String source, String destination) {
  final src = File(source);
  if (!src.existsSync()) {
    throw BuildException('Source file not found: $source');
  }
  final dest = File(destination);
  ensureDir(dest.parent.path);
  src.copySync(destination);
  _log.fine('Copied $source -> $destination');
}

String joinPath(String part1, [String? part2, String? part3, String? part4]) {
  return p.join(part1, part2, part3, part4);
}
