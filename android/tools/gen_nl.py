#!/usr/bin/env python3
"""Fills res/values-nl/<file> from res/values/<file> with the iOS app's Dutch strings.

Usage: tools/gen_nl.py strings_live.xml [--extra extra.json]
Looks each English text up in tools/ios-strings-nl.json (Android %1$s/%1$d are matched as iOS
%@/%lld). Strings already in the Dutch file are kept. Prints the ones it could not find; add those
by hand (or in --extra, a JSON {name: dutch}).
"""
import json, re, sys, os, xml.etree.ElementTree as ET
root = os.path.dirname(os.path.abspath(__file__))
res = os.path.join(root, '..', 'app', 'src', 'main', 'res')
name = sys.argv[1]
extra = {}
if '--extra' in sys.argv:
    extra = json.load(open(sys.argv[sys.argv.index('--extra') + 1]))
ios = json.load(open(os.path.join(root, 'ios-strings-nl.json')))
def unesc(s): return s.replace("\\'", "'").replace('\\"', '"')
def esc(s): return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", "\\'").replace('"', '\\"')
def to_ios(s):
    s = re.sub(r'%(\d)\$s', '%@', s); s = re.sub(r'%(\d)\$d', '%lld', s)
    return s.replace('%s', '%@').replace('%d', '%lld')
def from_ios(nl, en):
    # positional: iOS %1$@ / %@ in order → Android %n$s / %n$d following the English types
    types = re.findall(r'%(\d)\$([sd])', en)
    i = [0]
    def rep(m):
        if m.group(1): idx = int(m.group(1))
        else:
            i[0] += 1; idx = i[0]
        t = dict((int(a), b) for a, b in types).get(idx, 's' if m.group(2) == '@' else 'd')
        return f'%{idx}${t}'
    return re.sub(r'%(?:(\d)\$)?(@|lld|ld|d)', rep, nl)
src = ET.parse(os.path.join(res, 'values', name)).getroot()
dst_path = os.path.join(res, 'values-nl', name)
have = {}
if os.path.exists(dst_path):
    for e in ET.parse(dst_path).getroot():
        have[e.get('name')] = e
out, missing = [], []
for e in src:
    n = e.get('name')
    if n in have:
        out.append(ET.tostring(have[n], encoding='unicode').strip()); continue
    if e.tag == 'string':
        en = unesc(e.text or '')
        nl = extra.get(n) or ios.get(to_ios(en)) or ios.get(en)
        if nl and not nl.startswith('{'):
            out.append(f'<string name="{n}">{esc(from_ios(nl, en))}</string>')
        else:
            missing.append((n, en))
    else:
        missing.append((n, '(plurals)'))
with open(dst_path, 'w') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
    for o in out: f.write('    ' + o + '\n')
    f.write('</resources>\n')
for n, en in missing: print('MISSING', n, '|', en)
