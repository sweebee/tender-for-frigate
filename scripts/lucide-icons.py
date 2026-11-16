#!/usr/bin/env python3
"""Builds LucideIcons.json for the iOS and Android apps from Frigate's web UI (react-icons' Lucide set), so the
app draws camera group icons exactly as Frigate does. Each icon becomes one SVG path on a 24x24
grid, drawn as a 2-point stroke. Usage: scripts/lucide-icons.py [path to frigate/web]
Lucide is ISC licensed (https://lucide.dev/license); react-icons is MIT."""
import json, re, sys, os

web = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', '..', 'frigate', 'web')
src = open(os.path.join(web, 'node_modules', 'react-icons', 'lu', 'index.mjs')).read()

def f(x): return float(x)
def n(v):
    s = ('%.3f' % v).rstrip('0').rstrip('.')
    return s if s not in ('-0', '') else '0'

def ellipse(cx, cy, rx, ry):
    return (f'M{n(cx - rx)} {n(cy)}a{n(rx)} {n(ry)} 0 1 0 {n(2 * rx)} 0'
            f'a{n(rx)} {n(ry)} 0 1 0 {n(-2 * rx)} 0z')

def rect(a):
    x, y, w, h = f(a.get('x', 0)), f(a.get('y', 0)), f(a['width']), f(a['height'])
    rx = f(a.get('rx', a.get('ry', 0))); ry = f(a.get('ry', a.get('rx', 0)))
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx == 0 and ry == 0:
        return f'M{n(x)} {n(y)}h{n(w)}v{n(h)}h{n(-w)}z'
    return (f'M{n(x + rx)} {n(y)}h{n(w - 2 * rx)}a{n(rx)} {n(ry)} 0 0 1 {n(rx)} {n(ry)}'
            f'v{n(h - 2 * ry)}a{n(rx)} {n(ry)} 0 0 1 {n(-rx)} {n(ry)}h{n(-(w - 2 * rx))}'
            f'a{n(rx)} {n(ry)} 0 0 1 {n(-rx)} {n(-ry)}v{n(-(h - 2 * ry))}a{n(rx)} {n(ry)} 0 0 1 {n(rx)} {n(-ry)}z')

def points(p, close):
    nums = re.findall(r'-?[\d.]+(?:e-?\d+)?', p)
    pts = [nums[i:i + 2] for i in range(0, len(nums) - 1, 2)]
    return 'M' + 'L'.join(f'{a} {b}' for a, b in pts) + ('z' if close else '')

NUM = r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?'

def path(d):
    # A path that starts with a relative move starts from 0,0 (SVG); joined to the previous
    # element it would start from that one's end. Its first pair is absolute, later pairs are
    # relative lines.
    d = d.strip()
    m = re.match(r'm\s*(' + NUM + r')[\s,]*(' + NUM + r')', d)
    if not m: return d
    rest = d[m.end():].lstrip(' ,')
    return f'M{m.group(1)} {m.group(2)}' + (('l' + rest) if rest and not rest[0].isalpha() else rest)

def element(tag, a):
    if tag == 'path': return path(a['d'])
    if tag == 'circle': return ellipse(f(a['cx']), f(a['cy']), f(a['r']), f(a['r']))
    if tag == 'ellipse': return ellipse(f(a['cx']), f(a['cy']), f(a['rx']), f(a['ry']))
    if tag == 'rect': return rect(a)
    if tag == 'line': return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag == 'polyline': return points(a['points'], False)
    if tag == 'polygon': return points(a['points'], True)
    raise ValueError(tag)

icons = {}
for m in re.finditer(r'export function (Lu\w+) \(props\) \{\n  return GenIcon\((\{.*?\})\)\(props\);', src):
    name, data = m.group(1), json.loads(m.group(2))
    parts = []
    def walk(node):
        for c in node['child']:
            parts.append(element(c['tag'], c['attr'])); walk(c)
    walk(data)
    icons[name] = ''.join(parts)

# The same file for both apps.
here = os.path.dirname(__file__)
for out in (os.path.join(here, '..', 'ios', 'Frigate', 'Resources', 'LucideIcons.json'),
            os.path.join(here, '..', 'android', 'app', 'src', 'main', 'assets', 'LucideIcons.json')):
    if not os.path.isdir(os.path.dirname(out)):
        continue
    json.dump(dict(sorted(icons.items())), open(out, 'w'), separators=(',', ':'))
    print(f'{len(icons)} icons -> {os.path.normpath(out)} ({os.path.getsize(out) // 1024} KB)')
