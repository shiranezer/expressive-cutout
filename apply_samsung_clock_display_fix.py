#!/usr/bin/env python3
"""Display Samsung Clock's elapsed-realtime countdown using whole seconds.

Apply AFTER apply_samsung_clock_fix_v2.py, from the repository root:
    python3 apply_samsung_clock_display_fix.py --check
    python3 apply_samsung_clock_display_fix.py

All other clock apps keep their current ceiling-to-second presentation.
This changes only display formatting; timer deadlines and pause/resume stay intact.
"""
from pathlib import Path
import sys

TARGET = Path('app/src/main/java/com/ekoehler/expressivecutout/overlay/DynamicIsland.kt')


def exactly_one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise ValueError(f'{label}: expected 1 match, found {count}')
    return text.replace(old, new, 1)


def prepare(text: str) -> str:
    text = exactly_one(
        text,
        '            timerRemainingText()?.let { remaining ->',
        '            timerRemainingText(event.packageName)?.let { remaining ->',
        'collapsed timer call',
    )
    text = exactly_one(
        text,
        '                        text = timerRemainingText() ?: event.label,',
        '                        text = timerRemainingText(event.packageName) ?: event.label,',
        'expanded timer call',
    )
    text = exactly_one(
        text,
        'private fun timerRemainingText(): String? {',
        'private fun timerRemainingText(sourcePackage: String?): String? {',
        'timer formatter signature',
    )
    text = exactly_one(
        text,
        '    return formatCallDuration((remainingMs + 999L) / 1_000L)',
        '''    val seconds = if (sourcePackage == "com.sec.android.app.clockpackage") {
        remainingMs / 1_000L
    } else {
        (remainingMs + 999L) / 1_000L
    }
    return formatCallDuration(seconds)''',
        'Samsung-specific whole-second formatting',
    )
    text = exactly_one(
        text,
        ' * rounded up so a fresh 5:00 timer reads "5:00", and it lands on "0:00" exactly at zero.',
        ' * rounded up for other clock apps; Samsung Clock displays whole seconds without rounding up.',
        'timer formatter KDoc',
    )
    return text


def main() -> int:
    if not TARGET.is_file():
        print(f'ERROR: missing {TARGET}. Run this from the repository root.', file=sys.stderr)
        return 1
    original = TARGET.read_text(encoding='utf-8')
    try:
        modified = prepare(original)
    except ValueError as e:
        print(f'ERROR: {e}', file=sys.stderr)
        return 1
    if '--check' in sys.argv:
        print('PASS: all 5 display patch anchors found. No files changed.')
        return 0
    TARGET.write_text(modified, encoding='utf-8')
    print(f'UPDATED: {TARGET}')
    print('Samsung-only display formatting patched. Build and device validation required.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
