# SPDX-License-Identifier: Apache-2.0
"""Create an offline before/after gallery from screenshot candidates."""

import argparse
import html
import json
from pathlib import Path, PurePosixPath
from urllib.parse import quote

REFERENCE_ROOT = PurePosixPath("app/src/screenshotTestDefaultEmulatorDebug/reference")


def relative_reference(name):
    path = PurePosixPath(name).relative_to(REFERENCE_ROOT)
    if not path.parts or ".." in path.parts or path.suffix.lower() != ".png":
        raise ValueError(f"Invalid reference path: {name}")
    return path


def image(root, kind, path):
    file = root / kind / Path(*path.parts)
    if not file.is_file():
        return '<p class="missing">No image for this side</p>'
    src = html.escape(quote((PurePosixPath(kind) / path).as_posix()))
    return f'<a href="{src}"><img src="{src}" loading="lazy" alt="{kind} image"></a>'


def generate(root):
    context = json.loads((root / "source-context.json").read_text(encoding="utf-8"))
    groups = {}
    for entry in context["changes"]:
        path = relative_reference(entry["path"])
        operation = entry["operation"]
        if operation not in ("add", "replace", "delete"):
            raise ValueError(f"Unsupported operation: {operation}")
        if operation != "add" and not (root / "before" / Path(*path.parts)).is_file():
            raise ValueError(f"Missing reference image: {path}")
        if operation != "delete" and not (root / "after" / Path(*path.parts)).is_file():
            raise ValueError(f"Missing rendered image: {path}")
        parts = path.parts
        group = (
            parts[parts.index("jlmodplus") + 1]
            if "jlmodplus" in parts and len(parts) > parts.index("jlmodplus") + 1
            else "runtime"
        )
        groups.setdefault(group, []).append((path, operation))

    page = ['<!doctype html><html lang="en"><head><meta charset="utf-8">',
            '<meta name="viewport" content="width=device-width,initial-scale=1">',
            '<title>JL-Mod Plus screenshot review</title>',
            """<style>
:root{color-scheme:light dark;font:15px/1.5 system-ui,sans-serif}
body{max-width:1500px;margin:0 auto;padding:20px;background:#f3f5f7;color:#23313b}
h1{font-size:1.5rem}h2{text-transform:capitalize;margin-top:26px}
small{overflow-wrap:anywhere;color:#5b6873}.notice{background:#e5ebf0;padding:12px;border-radius:12px}
article{background:white;border:1px solid #cdd6dc;padding:14px;border-radius:16px;margin-bottom:18px}
h3{font-size:1.05rem;margin:0 0 4px;overflow-wrap:anywhere}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:12px;margin-top:10px}
figure{margin:0;min-width:0;background:#edf1f4;border-radius:10px;padding:10px}
figcaption{font-weight:600;margin-bottom:8px}figure img{max-width:100%;max-height:820px;height:auto;display:block;margin:auto}
.missing{text-align:center;padding:42px 0}
@media(prefers-color-scheme:dark){
body{background:#131a1e;color:#e2e8ec}small{color:#aab9c1}
.notice{background:#2a3941}article{background:#202a30;border-color:#48555c}
figure{background:#303b42}}
</style></head><body>""",
            '<h1>JL-Mod Plus · Screenshot visual review</h1>',
            f'<p>{len(context["changes"])} candidate images</p>',
            '<p class="notice">Review layout, typography, contrast, clipping and adaptive behavior. '
            'No baseline is approved automatically; static previews do not prove device interactions.</p>',
            f'<small>Head {html.escape(str(context.get("pr_head_commit", "")))} · ',
            f'Base {html.escape(str(context.get("pr_base_commit", "")))} · ',
            f'Build {html.escape(str(context.get("build_commit", "")))} · ',
            f'Run {html.escape(str(context.get("run_id", "")))}</small>']
    for group, items in sorted(groups.items()):
        page.append(f'<h2>{html.escape(group)} · {len(items)}</h2>')
        for path, operation in items:
            name = path.name.rsplit("_", 2)[0].replace("Screenshot_", " · ")
            page += ['<article>', f'<h3>{html.escape(name)}</h3>',
                     f'<small>{html.escape(operation)} · {html.escape(path.as_posix())}</small>',
                     '<div class="grid">']
            for kind in ("after", "before", "diff"):
                if kind == "diff" and not (root / kind / Path(*path.parts)).is_file():
                    continue
                page.append(f'<figure><figcaption>{kind.title()}</figcaption>{image(root, kind, path)}</figure>')
            page += ['</div>', '</article>']
    page.append('</body></html>')
    (root / "review.html").write_text("\n".join(page), encoding="utf-8")
    print(f"Generated offline review gallery for {len(context['changes'])} screenshots")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("screenshot-candidates"))
    generate(parser.parse_args().root)
