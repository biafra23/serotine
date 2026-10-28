#!/usr/bin/env python3
"""Generate the rate widget's asset icons from ui/.../TokenIcons.kt.

Usage: python3 scripts/gen_widget_icons.py

Android's RemoteViews, which draws the home-screen widget (android-app/.../widget/RateWidget.kt),
cannot draw a Compose ImageVector: it needs a drawable resource. Rather than hand-copy
ui/src/commonMain/kotlin/eth/serotine/ui/components/TokenIcons.kt's path data and colours into
Android VectorDrawable XML (and risk the two silently drifting apart), this script scrapes each
icon straight out of that file's own ImageVector.Builder calls — same path-data mini-language,
since Compose's addPathNodes() and Android's android:pathData both parse the SVG path grammar —
and writes:
  - android-app/src/main/res/drawable/ic_widget_asset_<key>.xml, one per TokenIcons key;
  - android-app/src/main/kotlin/eth/serotine/android/widget/WidgetAssetIcons.kt, the lookup
    RateWidget draws with.
CI re-runs it and fails on a diff (ci.yml, mirroring "Verify generated Android icons"), so the
widget's icons never drift from TokenIcons.kt's.
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'ui', 'src', 'commonMain', 'kotlin', 'eth', 'serotine', 'ui', 'components', 'TokenIcons.kt')
RES_DIR = os.path.join(ROOT, 'android-app', 'src', 'main', 'res', 'drawable')
KT_OUT = os.path.join(
    ROOT, 'android-app', 'src', 'main', 'kotlin', 'eth', 'serotine', 'android', 'widget', 'WidgetAssetIcons.kt',
)

with open(SRC, encoding='utf-8') as fh:
    TEXT = fh.read()


def balanced(s, open_idx):
    """The text strictly between the '(' at open_idx and its matching ')'."""
    assert s[open_idx] == '('
    depth = 0
    for i in range(open_idx, len(s)):
        if s[i] == '(':
            depth += 1
        elif s[i] == ')':
            depth -= 1
            if depth == 0:
                return s[open_idx + 1:i]
    raise ValueError('unbalanced parens from %d' % open_idx)


def lazy_block(key):
    """The body of `private val <key>: ImageVector by lazy { ... }` (one line of braces: no
    further { } nests inside, since every call inside uses only parens)."""
    m = re.search(r'private val %s: ImageVector by lazy \{\n' % re.escape(key), TEXT)
    if not m:
        raise ValueError('no lazy ImageVector named %r in %s' % (key, SRC))
    end = TEXT.index('\n    }', m.end())
    return TEXT[m.end():end]


def icon_viewport(block):
    """The viewport `icon("name"[, unitsf])` draws at: 32 (the CC0 set) unless given otherwise."""
    m = re.search(r'icon\("(\w+)"(?:,\s*([\d.]+)f)?\)', block)
    return int(float(m.group(2))) if m.group(2) else 32


def add_paths(block):
    """Each `.addPath(...)` call in `block`, as dicts with data/fill/alpha/type."""
    out = []
    for m in re.finditer(r'\.addPath\(', block):
        call = balanced(block, m.end() - 1)
        nodes_m = re.search(r'addPathNodes\(', call)
        nodes_call = balanced(call, nodes_m.end() - 1)
        path_data = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', nodes_call))
        rest = call[nodes_m.end() + len(nodes_call) + 1:]
        if 'Brush.linearGradient' in rest:
            grad_m = re.search(r'Brush\.linearGradient\(', rest)
            grad = balanced(rest, grad_m.end() - 1)
            colors = re.findall(r'Color\((0x[0-9A-Fa-f]+)\)', grad)
            start = re.search(r'start = Offset\(([\d.]+)f,\s*([\d.]+)f\)', grad)
            end = re.search(r'end = Offset\(([\d.]+)f,\s*([\d.]+)f\)', grad)
            fill = {
                'gradient': True,
                'colors': [c.replace('0x', '#') for c in colors],
                'start': (start.group(1), start.group(2)),
                'end': (end.group(1), end.group(2)),
            }
        elif re.search(r'SolidColor\(background\)', rest):
            # ethGlyph/daiGlyph's own background colour, resolved per key below.
            fill = {'gradient': False, 'color': None}
        else:
            color_m = re.search(r'SolidColor\(Color\((0x[0-9A-Fa-f]+)\)\)', rest)
            if not color_m:
                raise ValueError('unrecognised fill in .addPath(...): %r' % rest[:80])
            fill = {'gradient': False, 'color': color_m.group(1).replace('0x', '#')}
        alpha_m = re.search(r'fillAlpha = ([\d.]+)f?', rest)
        type_m = re.search(r'pathFillType = PathFillType\.(\w+)', rest)
        out.append({
            'data': path_data,
            'fill': fill,
            'alpha': alpha_m.group(1) if alpha_m else None,
            'type': 'evenOdd' if type_m and type_m.group(1) == 'EvenOdd' else None,
        })
    return out


# The keys whose lazy block is one flat `icon("x", units?).addPath(...)*.build()`: scraped
# whole, one block per key.
SIMPLE_KEYS = ['usdc', 'link', 'uni', 'gno', 'eure', 'usdt', 'wbtc', 'aave', 'eurc', 'pyusd', 'gho']

icons = {}
for key in SIMPLE_KEYS:
    block = lazy_block(key)
    icons[key] = {'viewport': icon_viewport(block), 'paths': add_paths(block)}

# eth/weth and dai/xdai/wxdai/sdai each share one glyph (ethGlyph/daiGlyph) over their own
# background colour, so that shared body is scraped once per helper and then applied with
# each key's own background.


def helper_body(name):
    pattern = r'private fun %s\(name: String, background: Color\): ImageVector =\n(.*?)\n        \.build\(\)' % name
    m = re.search(pattern, TEXT, re.DOTALL)
    if not m:
        raise ValueError('no helper function named %r in %s' % (name, SRC))
    return m.group(1) + '\n        .build()'


ETH_GLYPH = add_paths(helper_body('ethGlyph'))
DAI_GLYPH = add_paths(helper_body('daiGlyph'))

BACKGROUNDS = {
    'eth': '0xFF627EEA', 'weth': '0xFFEC1C79',
    'dai': '0xFFF4B731', 'xdai': '0xFF48A9A6', 'wxdai': '0xFF3E6957', 'sdai': '0xFFB8741A',
}
for key, glyph in (('eth', ETH_GLYPH), ('weth', ETH_GLYPH), ('dai', DAI_GLYPH), ('xdai', DAI_GLYPH),
                   ('wxdai', DAI_GLYPH), ('sdai', DAI_GLYPH)):
    paths = []
    for p in glyph:
        fill = p['fill']
        if not fill['gradient'] and fill['color'] is None:
            fill = {'gradient': False, 'color': BACKGROUNDS[key].replace('0x', '#')}
        paths.append({**p, 'fill': fill})
    icons[key] = {'viewport': 32, 'paths': paths}

EXPECTED_KEYS = {
    'eth', 'usdc', 'link', 'uni', 'weth', 'eurc', 'pyusd', 'gho', 'dai', 'usdt', 'wbtc', 'aave',
    'xdai', 'wxdai', 'sdai', 'gno', 'eure',
}
if set(icons) != EXPECTED_KEYS:
    raise ValueError('scraped %s, expected %s' % (sorted(icons), sorted(EXPECTED_KEYS)))


def vector_xml(key, spec):
    """An Android VectorDrawable of `spec`'s paths, each path exactly as TokenIcons.kt drew it."""
    vp = spec['viewport']
    needs_aapt = any(p['fill']['gradient'] for p in spec['paths'])
    root_attrs = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    if needs_aapt:
        root_attrs += '\n    xmlns:aapt="http://schemas.android.com/aapt"'
    paths_xml = []
    for p in spec['paths']:
        attrs = ['            android:pathData="%s"' % p['data']]
        if p['type']:
            attrs.insert(0, '            android:fillType="%s"' % p['type'])
        if p['alpha']:
            attrs.insert(0 if not p['type'] else 1, '            android:fillAlpha="%s"' % p['alpha'])
        if p['fill']['gradient']:
            attrs.insert(0, '            android:fillColor="#00000000"')
            body = '\n'.join(attrs)
            start, end = p['fill']['start'], p['fill']['end']
            colors = p['fill']['colors']
            paths_xml.append(
                '        <path\n%s>\n'
                '            <aapt:attr name="android:fillColor">\n'
                '                <gradient\n'
                '                    android:type="linear"\n'
                '                    android:startX="%s" android:startY="%s"\n'
                '                    android:endX="%s" android:endY="%s"\n'
                '                    android:startColor="%s" android:endColor="%s" />\n'
                '            </aapt:attr>\n'
                '        </path>' % (body, start[0], start[1], end[0], end[1], colors[0], colors[1])
            )
        else:
            attrs.insert(0, '            android:fillColor="%s"' % p['fill']['color'])
            paths_xml.append('        <path\n%s />' % '\n'.join(attrs))
    header = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- GENERATED by scripts/gen_widget_icons.py from ui/.../TokenIcons.kt's `%s` icon\n"
        "     (its own licence notice there): the rate widget's asset icon, drawn by\n"
        '     RemoteViews, which cannot draw a Compose ImageVector directly. Edit TokenIcons.kt\n'
        '     and re-run; CI fails on a hand edit here. -->\n' % key
    )
    return (
        header +
        '<vector %s\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="%d"\n'
        '    android:viewportHeight="%d">\n'
        '%s\n'
        '</vector>\n' % (root_attrs, vp, vp, '\n'.join(paths_xml))
    )


def write(path, text):
    # newline='\n': text mode would otherwise write CRLF on Windows, which the CI drift gate
    # then reports as stale right after a regenerate (as gen_android_icons.py notes).
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)


for key, spec in icons.items():
    write(os.path.join(RES_DIR, 'ic_widget_asset_%s.xml' % key), vector_xml(key, spec))

kt = (
    'package eth.serotine.android.widget\n\n'
    'import eth.serotine.android.R\n\n'
    '/**\n'
    ' * The rate widget\'s copy of [eth.serotine.ui.components.TokenIcons] as Android\n'
    ' * VectorDrawables (`ic_widget_asset_<key>.xml`), since [android.widget.RemoteViews] draws\n'
    ' * a drawable resource, never a Compose `ImageVector`. GENERATED by\n'
    ' * scripts/gen_widget_icons.py from TokenIcons.kt; CI fails on a hand edit.\n'
    ' */\n'
    'object WidgetAssetIcons {\n\n'
    '    /** The drawable resource of [key] ([eth.serotine.ui.components.TokenIcons.of]\'s keys), or null for none. */\n'
    '    fun of(key: String): Int? = when (key) {\n'
)
for key in sorted(icons):
    kt += '        "%s" -> R.drawable.ic_widget_asset_%s\n' % (key, key)
kt += (
    '        else -> null\n'
    '    }\n'
    '}\n'
)
write(KT_OUT, kt)

print('wrote %d drawables to %s' % (len(icons), RES_DIR))
print('wrote %s' % KT_OUT)
