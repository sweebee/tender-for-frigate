#!/bin/sh
# Looks up the Dutch translation the iOS app uses for an English string (substring match).
# Usage: tools/nl.sh "Mark as reviewed"
python3 -c "
import json,sys
d=json.load(open('$(dirname "$0")/ios-strings-nl.json'))
q=sys.argv[1].lower()
for k,v in d.items():
    if q in k.lower(): print(repr(k),'=>',repr(v))
" "$1"
