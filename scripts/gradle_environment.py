"""Read the checked-in wrapper identity instead of pinning test helpers separately."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def pinned_version():
    properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    return re.search(r'gradle-([\d.]+)-bin\.zip', properties)[1]


def pgp_jars(home):
    version = pinned_version()
    return sorted((home / f'wrapper/dists/gradle-{version}-bin').glob(
        f'*/gradle-{version}/lib/plugins/bc*-jdk18on-*.jar'))
