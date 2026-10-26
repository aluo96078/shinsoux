#!/usr/bin/env python3
"""Counts selector runs whose result count (or error state) differs from jsoup.
usage: parity_summary.py expected.txt actual.txt corpus-selectors.txt extra-selectors.txt"""
import sys
def load(p):
    d, fx, sel = {}, None, None
    for l in open(p, encoding='utf-8', errors='replace').read().split('\n'):
        if l.startswith('### '): fx = l[4:]
        elif l.startswith('SEL '): sel = l[4:]; d[(fx, sel)] = None
        elif l.startswith('  count=') and sel is not None: d[(fx, sel)] = int(l[8:])
        elif l == '  ERROR' and sel is not None: d[(fx, sel)] = 'ERROR'
    return d
exp, act = load(sys.argv[1]), load(sys.argv[2])
corpus = set(x for x in open(sys.argv[3], encoding='utf-8').read().split('\n') if x)
extra = set(x for x in open(sys.argv[4], encoding='utf-8').read().split('\n') if x) - corpus
for name, group in (('official-corpus selectors', corpus), ('extra jsoup-specific selectors', extra)):
    runs = [(k, v) for k, v in exp.items() if k[1] in group]
    diff = [k for k, v in runs if act.get(k) != v]
    print('%-32s %5d runs, %4d differ (%.1f%%), %d distinct selectors differ' % (name, len(runs), len(diff), 100.0 * len(diff) / max(1, len(runs)), len(set(k[1] for k in diff))))
    if '--list' in sys.argv:
        for s in sorted(set(k[1] for k in diff)): print('   ', s)
