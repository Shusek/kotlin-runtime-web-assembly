# Documentation

This repository publishes two documentation surfaces to GitHub Pages:

- descriptive documentation built with Zensical from `docs/pages`,
- generated API reference built with Dokka and published under `/api`.

Build locally from the repository root:

```shell
# Use Python 3.12 or newer for Zensical.
python3.12 -m venv /tmp/krwa-zensical
/tmp/krwa-zensical/bin/python -m pip install -r docs/requirements.txt
/tmp/krwa-zensical/bin/zensical build --clean
./gradlew :dokkaGenerate
```

Zensical writes the descriptive site to `site`. Dokka writes generated API
documentation to `build/dokka/html`.

The GitHub Pages workflow copies them into one artifact:

- `site/` becomes the root documentation site,
- `build/dokka/html/` becomes `/api/`,
- immutable commit-derived development artifacts are published under `/maven/`.
