"""Check the real merged library Manifest before a consumer resolves placeholders."""
import io
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'


def verify_aar_manifest(aar):
    with zipfile.ZipFile(aar) as archive:
        manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
        with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as jar:
            classes = set(jar.namelist())
    assert manifest.get('package') == 'com.fataar.sdk'
    assert manifest.find('uses-sdk').get(ANDROID + 'minSdkVersion') == '24'
    assert manifest.find("queries/provider").get(ANDROID + "authorities") == "${applicationId}.fixture"
    application = manifest.find('application')

    def active(nodes):
        return [node for node in nodes if node.get(TOOLS + 'node') not in ('remove', 'removeAll')]

    for kind in ('activity', 'service', 'receiver', 'provider'):
        name = 'com.fataar.fixtures.manifestcomponents.Fixture' + kind.capitalize()
        matches = [node for node in active(application.findall(kind)) if node.get(ANDROID + 'name') == name]
        assert len(matches) == 1, (kind, name)
        assert matches[0].get(ANDROID + 'exported') == 'false'
        assert name.replace('.', '/') + '.class' in classes
        if kind == 'provider':
            assert matches[0].get(ANDROID + 'authorities') == '${applicationId}.fixture'
    metadata = {node.get(ANDROID + 'name'): node.get(ANDROID + 'value') for node in active(application.findall('meta-data'))}
    assert metadata['com.fataar.fixture'] == 'manifest-present'
    assert metadata['com.fataar.fixture.override'] == 'sdk-value'
    assert metadata['com.fataar.fixture.channel'] == '${fataarChannel}'
    assert 'com.fataar.fixture.removed' not in metadata
    permissions = {node.get(ANDROID + 'name') for node in active(manifest.findall('uses-permission'))}
    assert 'android.permission.ACCESS_NETWORK_STATE' in permissions
    assert 'android.permission.VIBRATE' not in permissions
    return {'components': 4, 'queryProviderDistinctFromComponent': True, 'relativeNames': True, 'toolsReplace': True, 'toolsRemove': True,
            'deferredPlaceholders': ['applicationId', 'fataarChannel']}
