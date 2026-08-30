from pathlib import Path

for path, anchor, line in (
    (
        'app/src/main/res/values/memory_editor_strings.xml',
        '    <string name="memory_editor_next_scan">Next Scan</string>\n',
        '    <string name="memory_editor_refine_results">Refine results</string>\n',
    ),
    (
        'app/src/main/res/values-in/memory_editor_strings.xml',
        '    <string name="memory_editor_next_scan">Pindai Berikutnya</string>\n',
        '    <string name="memory_editor_refine_results">Persempit hasil</string>\n',
    ),
):
    p = Path(path)
    text = p.read_text()
    if 'name="memory_editor_refine_results"' in text:
        continue
    if text.count(anchor) != 1:
        raise SystemExit(f'{path}: next_scan anchor count={text.count(anchor)}')
    p.write_text(text.replace(anchor, anchor + line, 1))
