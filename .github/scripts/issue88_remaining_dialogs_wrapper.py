from pathlib import Path

script_path = Path('.github/scripts/issue88_remaining_dialogs.py')
source = script_path.read_text()
old = '''    """            int maxWidth = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 480, getResources().getDisplayMetrics());
            int width = Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels - margin);""",'''
new = '''    """\\t\\t\\tint maxWidth = (int) TypedValue.applyDimension(
\\t\\t\\t\\t\\tTypedValue.COMPLEX_UNIT_DIP, 480, getResources().getDisplayMetrics());
\\t\\t\\tint width = Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels - margin);""",'''
if source.count(old) != 1:
    raise SystemExit(f'Expected one installer marker in temporary patcher, found {source.count(old)}')
exec(compile(source.replace(old, new, 1), str(script_path), 'exec'))
