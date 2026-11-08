#!/usr/bin/env python3
"""Rebuild six coherent Media3 AARs from official stock inputs and preferred source.

Custom reference class bytes are never read by this builder. Unchanged stock
classes retain their official artifact identity and corresponding public source.
Allocator native inputs are built separately from the companion native recipe.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
MODULES = ['common', 'datasource', 'datasource-okhttp', 'exoplayer', 'exoplayer-hls', 'extractor']
ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def packed(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as package:
        for name, body in sorted(files.items()):
            assert not name.startswith('/') and '..' not in Path(name).parts
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            package.writestr(entry, body, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def classes(data):
    with zipfile.ZipFile(io.BytesIO(data)) as package:
        return {name: package.read(name) for name in package.namelist() if name.endswith('.class')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--android', type=Path, required=True)
    parser.add_argument('--gradle-cache', type=Path, required=True)
    parser.add_argument('--stock-aars', type=Path, required=True, help='Directory of pinned google-MODULE.aar official artifacts')
    parser.add_argument('--allocator-native', type=Path, required=True, help='Directory ABI/libmedia3_exoplayer_jni.so from companion hardened-native recipe')
    parser.add_argument('--output', type=Path, required=True, help='Fresh owned output directory; existing paths refused')
    parser.add_argument('--test', action='store_true', help='Run actual decoder/subtitle preservation checks')
    parser.add_argument('--mockable-android', type=Path, help='AGP mockable Android jar, required for host checks')
    args = parser.parse_args()
    assert not args.output.exists(), 'Use a fresh output directory; qualification proofs are immutable'
    modules = json.loads((ROOT / 'module-inputs.json').read_text())['modules']
    preferred = json.loads((ROOT / 'preferred-sources.json').read_text())
    assert len(preferred['sources']) == 33
    compiler = subprocess.check_output([str(args.jdk / 'bin/javac'), '-version'], text=True, stderr=subprocess.STDOUT).strip()
    assert compiler == 'javac 17.0.21', compiler
    source_files, source_modules = [], {}
    for relative, record in preferred['sources'].items():
        path = ROOT / relative
        assert digest(path.read_bytes()) == record['sha256'], relative
        assert record['module'] in MODULES
        source_files.append(path)
        stem = record['unit'].removesuffix('.java')
        assert stem not in source_modules
        source_modules[stem] = record['module']
    assert all((ROOT / name).is_file() and digest((ROOT / name).read_bytes()) == value
               for name, value in preferred['supportFiles'].items())
    args.output.mkdir(parents=True)
    inputs = {str(ROOT / 'preferred-sources.json'): digest((ROOT / 'preferred-sources.json').read_bytes()),
              str(ROOT / 'module-inputs.json'): digest((ROOT / 'module-inputs.json').read_bytes()),
              str(args.android): digest(args.android.read_bytes())}
    classpath, stock_classes = [], {}
    for module in MODULES:
        path = args.stock_aars / ('google-' + module + '.aar')
        payload = path.read_bytes()
        assert digest(payload) == modules[module]['officialStock']['sha256'], module
        inputs[str(path)] = digest(payload)
        with zipfile.ZipFile(io.BytesIO(payload)) as package:
            jar = package.read('classes.jar')
        assert digest(jar) == modules[module]['officialStock']['aarEntries']['classes.jar']
        target = args.output / 'dependencies' / (module + '.jar')
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(jar)
        classpath.append(target)
        stock_classes[module] = classes(jar)

    def dependency(group, name, version, extension='jar'):
        matches = list((args.gradle_cache / 'modules-2/files-2.1' / group / name / version).glob('*/*.' + extension))
        assert len(matches) == 1, (group, name, version, matches)
        path = matches[0]
        inputs[str(path)] = digest(path.read_bytes())
        if extension == 'aar':
            with zipfile.ZipFile(path) as package:
                jar = package.read('classes.jar')
            target = args.output / 'dependencies' / (name + '.jar')
            target.write_bytes(jar)
            return target
        return path

    classpath += [dependency(*item) for item in [
        ('androidx.annotation', 'annotation-jvm', '1.9.1'),
        ('com.google.guava', 'guava', '33.7.2-android'),
        ('com.google.guava', 'failureaccess', '1.0.3'),
        ('com.google.errorprone', 'error_prone_annotations', '2.30.0'),
        ('org.checkerframework', 'checker-qual', '3.43.0'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.0'),
        ('com.google.j2objc', 'j2objc-annotations', '3.1'),
        ('com.squareup.okio', 'okio-jvm', '3.7.0'),
    ]]
    classpath += [dependency('androidx.media3', 'media3-' + name, '1.8.0', 'aar')
                  for name in ['container', 'decoder', 'database']]
    classpath += [dependency('androidx.annotation', 'annotation-experimental', '1.4.1', 'aar'),
                  dependency('com.squareup.okhttp3', 'okhttp-android', '5.3.2', 'aar')]
    classpath.insert(0, args.android)
    emitted = args.output / 'classes'
    emitted.mkdir()
    flags = ['--release', '8', '-encoding', 'UTF-8', '-g', '-proc:none']
    command = [str(args.jdk / 'bin/javac'), *flags, '-cp', os.pathsep.join(map(str, classpath)),
               '-d', str(emitted), *map(str, sorted(source_files))]
    result = subprocess.run(command, text=True, capture_output=True)
    (args.output / 'javac.log').write_text(result.stdout + result.stderr)
    assert result.returncode == 0, result.stdout + result.stderr
    compiled = {module: {} for module in MODULES}
    for path in sorted(emitted.rglob('*.class')):
        name = path.relative_to(emitted).as_posix()
        unit = name.removesuffix('.class').split('$')[0]
        assert unit in source_modules, 'Unexpected compiled definition: ' + name
        compiled[source_modules[unit]][name] = path.read_bytes()
    receipts = {}
    for module in MODULES:
        record = modules[module]
        replacements = compiled[module]
        required = set(record['changedClasses'] + record['addedClasses'])
        assert required <= replacements.keys(), (module, 'Missing custom source', required - replacements.keys())
        values = dict(stock_classes[module])
        values.update(replacements)
        assert set(values) == set(record['expectedFinalClassInventory']), module
        assert all(values[name] == stock_classes[module][name]
                   for name in record['stockIdenticalClasses'] if name not in replacements)
        contents = {}
        for name, expected in record['aarMetadataFiles'].items():
            body = (ROOT / 'metadata' / module / 'aar' / name).read_bytes()
            assert digest(body) == expected, (module, name)
            contents[name] = body
        annotations = {}
        for name, expected in record['annotationXmlFiles'].items():
            body = (ROOT / 'metadata' / module / 'annotations' / name).read_bytes()
            assert digest(body) == expected, (module, name)
            annotations[name] = body
        if annotations:
            contents['annotations.zip'] = packed(annotations)
        contents['classes.jar'] = packed(values)
        natives = {}
        if module == 'exoplayer':
            for abi in ABIS:
                name = 'jni/' + abi + '/libmedia3_exoplayer_jni.so'
                body = (args.allocator_native / abi / 'libmedia3_exoplayer_jni.so').read_bytes()
                assert digest(body) == record['hardenedNativeSha256'][abi]
                contents[name] = body
                natives[name] = digest(body)
        body = packed(contents)
        assert body == packed(contents), 'Nondeterministic package serialization'
        name = 'lib-' + module + '-release.aar'
        (args.output / name).write_bytes(body)
        receipts[module] = {
            'aar': name, 'sha256': digest(body), 'bytes': len(body),
            'classes': {name: digest(value) for name, value in sorted(values.items())},
            'compiledClasses': sorted(replacements),
            'unchangedOfficialClassesCopied': len(values) - len(replacements),
            'customReferenceClassBytesUsed': 0, 'nativeEntries': natives,
            'entries': {name: digest(value) for name, value in sorted(contents.items())},
        }
    inputs.update({str(path): digest(path.read_bytes()) for path in source_files})
    if args.test:
        assert args.mockable_android is not None and args.mockable_android.is_file()
        tests = sorted((ROOT / 'tests').rglob('*.java'))
        assert len(tests) == 2
        output = args.output / 'test-classes'
        output.mkdir()
        testcp = [emitted, args.mockable_android, *classpath[1:]]
        commands = [
            [args.jdk / 'bin/javac', *flags, '-cp', os.pathsep.join(map(str, testcp)), '-d', output, *tests],
            [args.jdk / 'bin/java', '-cp', os.pathsep.join(map(str, [output, *testcp])), 'androidx.media3.extractor.mp4.CoreBehaviorChecks'],
        ]
        for index, command in enumerate(commands):
            result = subprocess.run(list(map(str, command)), text=True, capture_output=True)
            (args.output / ('host-check-' + str(index) + '.log')).write_text(result.stdout + result.stderr)
            assert result.returncode == 0, result.stdout + result.stderr
        assert 'PASS 29 decoder/subtitle assertions' in result.stdout
        inputs.update({str(path): digest(path.read_bytes()) for path in [args.mockable_android, *tests]})
    receipt = {'compiler': compiler, 'flags': flags, 'inputs': inputs, 'modules': receipts,
               'allSourcesCompiledTogether': True, 'sourceUnits': len(source_files),
               'classCount': sum(len(row['classes']) for row in receipts.values()),
               'hostBehaviorAssertionsPassed': 29 if args.test else None,
               'acceptedAsStable': False}
    (args.output / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({key: value for key, value in receipt.items() if key not in ['inputs', 'modules']}))


if __name__ == '__main__':
    main()
