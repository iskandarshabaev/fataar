"""Verify generated-code payload and resource ownership in the final, real SDK AAR."""
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


def json_bytes(data):
    return json.loads(data.decode('utf-16' if data[:2] in (b'\xfe\xff', b'\xff\xfe') else 'utf-8'))


def verify_generated_payload(root, aar):
    namespaces = ['com.fataar.sdk', 'com.fataar.fixtures.databindingleft', 'com.fataar.fixtures.databindingright']
    packets = 'aidl/com/fataar/fixtures/aidl/FixturePacket.aidl'
    classes_checked = ['com/fataar/fixtures/aidl/IFixtureBridge.class',
        'com/fataar/fixtures/aidl/IFixtureBridge$Stub$Proxy.class',
        'com/fataar/fixtures/aidl/FixturePacket.class', 'com/fataar/fixtures/aidl/FixtureAidlService.class',
        'com/fataar/fixtures/viewbinding/databinding/FixtureViewBindingBinding.class',
        'com/fataar/fixtures/databindingleft/FixtureBindingAdapters.class',
        'com/fataar/fixtures/databindingleft/databinding/FixtureDataBindingLeftBindingImpl.class',
        'com/fataar/fixtures/databindingright/databinding/FixtureDataBindingRightBindingImpl.class',
        'com/fataar/sdk/databinding/FixtureSdkBindingBindingImpl.class']
    originals = [root / 'sdk/build/intermediates/aar/release/bundleReleaseAar/sdk-release.aar',
                 *[root / f'fixtures/{m}/build/outputs/aar/{m}-release.aar' for m in ('aidl', 'data-binding-left', 'data-binding-right')]]
    with zipfile.ZipFile(aar) as result:
        entries = set(result.namelist())
        with zipfile.ZipFile(io.BytesIO(result.read('classes.jar'))) as jar:
            names = set(jar.namelist())
            assert set(classes_checked) <= names, set(classes_checked) - names
            assert 'androidx/databinding/DataBindingComponent.class' not in names
            assert not any(n.endswith('/BR.class') for n in names)
            assert not any(n.startswith('com/fataar/sdk/R$') or n == 'com/fataar/sdk/R.class' for n in names)
        for namespace in namespaces:
            assert {f'data-binding/{namespace}-br.bin', f'data-binding/{namespace}-setter_store.json',
                    f'data-binding-base-class-log/{namespace}-binding_classes.json'} <= entries
            assert namespace.replace('.', '/') + '/DataBinderMapperImpl.class' in names
        mappings = 0
        for original in originals:
            with zipfile.ZipFile(original) as source:
                for path in source.namelist():
                    if path.endswith('/'):
                        continue
                    if path.startswith('aidl/') or path.startswith('data-binding/'):
                        assert result.read(path) == source.read(path), (original, path)
                    elif path.startswith('data-binding-base-class-log/'):
                        before = json_bytes(source.read(path))
                        after = json_bytes(result.read(path))
                        for layout, value in before['mappings'].items():
                            expected = dict(value, module_package='com.fataar.sdk')
                            assert after['mappings'][layout] == expected, (original, layout)
                            mappings += 1
        adapter = json_bytes(result.read('data-binding/com.fataar.fixtures.databindingleft-setter_store.json'))
        assert 'FixtureBindingAdapters' in json.dumps(adapter) and 'fixtureBindingSuffix' in json.dumps(adapter)
        manifest = ET.fromstring(result.read('AndroidManifest.xml'))
        android = '{http://schemas.android.com/apk/res/android}'
        service = next(s for s in manifest.findall('application/service') if s.get(android+'name') == 'com.fataar.fixtures.aidl.FixtureAidlService')
        assert service.get(android+'process') == ':fataar_aidl' and service.get(android+'exported') == 'false'
    compile_jar = root / 'sdk/build/intermediates/fat-aar/debug/compile-classes.jar'
    runtime_jar = root / 'sdk/build/intermediates/fat-aar/debug/classes.jar'
    with zipfile.ZipFile(compile_jar) as jar:
        assert 'com/fataar/sdk/R$layout.class' in jar.namelist()
    with zipfile.ZipFile(runtime_jar) as jar:
        assert not any(n.startswith('com/fataar/sdk/R$') or n == 'com/fataar/sdk/R.class' for n in jar.namelist())
    return {'aidlDeclarations': 1, 'bindingMetadataFiles': 9, 'mappingRecords': mappings,
            'checkedClasses': len(classes_checked), 'resourceOwner': 'com.fataar.sdk',
            'brAndComponentGeneratedByConsumer': True, 'compileRExcludedFromRuntime': True}
