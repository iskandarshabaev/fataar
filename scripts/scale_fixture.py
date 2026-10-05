"""Generate owned real Android projects; never modify production fixtures."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def parents(index, shape):
    if shape == 'chain': return [index - 1] if index else []
    if shape == 'diamonds':
        if index < 3: return list(range(index))
        return sorted({index - 1, index - 2, max(0, index - 3)})
    if shape == 'fanout': return [0] if index else []
    raise ValueError(shape)


def generate(directory, count, shape, cache, payload_kib=64):
    def write(relative, text):
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text.strip() + '\n')
    modules = [f'm{i:03}' for i in range(count)]
    plugin = (ROOT / 'plugin-build').as_posix()
    catalog = (ROOT / 'gradle/libs.versions.toml').as_posix()
    write('settings.gradle.kts', f'''
        pluginManagement {{ includeBuild("{plugin}"); repositories {{ google(); mavenCentral(); gradlePluginPortal() }} }}
        dependencyResolutionManagement {{
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {{ google(); mavenCentral() }}
          versionCatalogs {{ create("libs") {{ from(files("{catalog}")) }} }}
        }}
        enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
        rootProject.name = "ScalePlayground"
        include(":sdk", {', '.join('":' + m + '"' for m in modules)})
        buildCache {{ local {{ directory = file("{cache.as_posix()}") }} }}
    ''')
    write('gradle.properties', '''
        org.gradle.caching=true
        org.gradle.configuration-cache=true
        org.gradle.configuration-cache.problems=fail
        org.gradle.workers.max=4
        org.gradle.parallel=true
        android.useAndroidX=true
        android.nonTransitiveRClass=true
        org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
    ''')
    write('build.gradle.kts', '''
        plugins { alias(libs.plugins.android.library) apply false }
        allprojects { group = "com.fataar.scale"; version = "1" }
    ''')
    expected = 0
    for i, module in enumerate(modules):
        ancestors = parents(i, shape)
        dependencies = '; '.join(f'api project(":m{p:03}")' for p in ancestors)
        write(f'{module}/build.gradle', f'''
            plugins {{ id 'com.android.library'; id 'com.fataar.embeddable' }}
            android {{ namespace = 'com.fataar.scale.{module}'; compileSdk = 36
              defaultConfig {{ minSdk = 24; consumerProguardFiles 'consumer-rules.pro' }}
              compileOptions {{ sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }}
            }}
            dependencies {{ {dependencies} }}
        ''')
        extra = ''.join(f' + com.fataar.scale.m{p:03}.Marker.marker()' for p in ancestors)
        write(f'{module}/src/main/java/com/fataar/scale/{module}/Marker.java', f'''
            package com.fataar.scale.{module};
            public final class Marker {{
              public static int marker() {{ return {i + 1}; }}
              public static int value(android.content.Context context) {{
                return marker() + context.getResources().getInteger(R.integer.scale_number_{module}){extra};
              }}
            }}
        ''')
        expected += 2 * (i + 1) + sum(p + 1 for p in ancestors)
        write(f'{module}/src/main/AndroidManifest.xml', f'''
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
              <meta-data android:name="scale.{module}" android:value="{i + 1}"/>
            </application></manifest>
        ''')
        write(f'{module}/src/main/res/values/strings.xml', f'''
            <resources><string name="scale_label_{module}">Module {module}</string>
              <integer name="scale_number_{module}">{i + 1}</integer></resources>
        ''')
        write(f'{module}/src/main/res/layout/scale_layout_{module}.xml', f'''
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
              android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="@string/scale_label_{module}"/>
        ''')
        payload = directory / f'{module}/src/main/assets/scale/{module}.bin'
        payload.parent.mkdir(parents=True, exist_ok=True)
        payload.write_bytes(hashlib.shake_256(module.encode()).digest(payload_kib * 1024))
        write(f'{module}/src/main/assets/scale/{module}-obsolete.txt', 'Must disappear when removed from input')
        write(f'{module}/consumer-rules.pro', f'-keep class com.fataar.scale.{module}.Marker {{ public static *; }}')
    write('sdk/build.gradle', '''
        plugins { id 'com.android.library'; id 'com.fataar'; id 'maven-publish' }
        android { namespace = 'com.fataar.scale.sdk'; compileSdk = 36
          defaultConfig { minSdk = 24 }
          compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        }
        dependencies { ''' + '; '.join(f'embed project(":{m}")' for m in modules) + ''' }
        publishing { repositories { maven { name = 'Scale'; url = rootProject.layout.buildDirectory.dir('repository') } } }
        components.configureEach { component -> if (component.name == 'fatRelease') {
          publishing.publications.register('sdk', MavenPublication) { from component }
        } }
    ''')
    write('sdk/src/main/AndroidManifest.xml', '<manifest/>')
    write('sdk/src/main/java/com/fataar/scale/sdk/ScaleSdk.java', '''
        package com.fataar.scale.sdk;
        public final class ScaleSdk {
          public static int value(android.content.Context context) {
            return ''' + '\n + '.join(f'com.fataar.scale.{m}.Marker.value(context)' for m in modules) + ''';
          }
        }
    ''')
    # The consumer is a separate build with one published SDK dependency.
    write('consumer/settings.gradle.kts', f'''
        pluginManagement {{ repositories {{ google(); mavenCentral(); gradlePluginPortal() }} }}
        dependencyResolutionManagement {{
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {{ maven {{ url = uri("../build/repository"); content {{ includeGroup("com.fataar.scale") }} }}; google(); mavenCentral() }}
          versionCatalogs {{ create("libs") {{ from(files("{catalog}")) }} }}
        }}
        enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
        rootProject.name = "PublishedScaleConsumer"; include(":app")
    ''')
    write('consumer/gradle.properties', (directory / 'gradle.properties').read_text())
    write('consumer/app/build.gradle.kts', '''
        plugins { alias(libs.plugins.android.application) }
        android { namespace = "com.fataar.scale.consumer"; compileSdk = 36
          defaultConfig { applicationId = "com.fataar.scale.consumer"; minSdk = 24; targetSdk = 36 }
          compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        }
        dependencies { implementation("com.fataar.scale:sdk:1") }
    ''')
    write('consumer/app/src/main/AndroidManifest.xml', '<manifest><application/></manifest>')
    write('consumer/app/src/main/java/com/fataar/scale/consumer/Consumer.java', '''
        package com.fataar.scale.consumer;
        public final class Consumer { public static int value(android.content.Context context) {
          return com.fataar.scale.sdk.ScaleSdk.value(context);
        } }
    ''')
    return {'modules': count, 'shape': shape, 'payloadKiBPerModule': payload_kib, 'expectedValue': expected}
