#!/usr/bin/env python3
"""Compare jsoup (expected.txt) with sandbox_dom (actual.txt) golden outputs.
usage: compare_golden.py expected.txt actual.txt [--samples N]"""
import re, sys, collections

def parse(path):
    data = collections.OrderedDict()
    fx = sel = None
    for line in open(path, encoding='utf-8', errors='replace').read().split('\n'):
        if line.startswith('### '):
            fx = line[4:]; data[fx] = {'DOC': {}, 'SEL': collections.OrderedDict()}; sel = None
        elif line.startswith('DOC.'):
            k, _, v = line.partition('='); data[fx]['DOC'][k] = v
        elif line.startswith('SEL '):
            sel = line[4:]; data[fx]['SEL'][sel] = {}
        elif line.startswith('  ') and sel is not None and not line.startswith('  #'):
            body = line[2:]
            k, _, v = body.partition('=')
            data[fx]['SEL'][sel][k] = v
    return data

def norm(v):
    v = re.sub(r'\\[nrt]', ' ', v)
    v = re.sub(r'\s+', ' ', v).strip()
    v = re.sub(r'\s*(<[^>]*>)\s*', r'\1', v)
    return v

def main():
    exp, act = parse(sys.argv[1]), parse(sys.argv[2])
    samples = 3
    counts_only = '--counts-only' in sys.argv
    if '--samples' in sys.argv: samples = int(sys.argv[sys.argv.index('--samples') + 1])
    stats = collections.defaultdict(lambda: [0, 0, 0, 0])   # total, exact, normalized-equal, diff
    diffs = collections.defaultdict(list)
    sel_issues = []
    sel_total = 0
    for fx in exp:
        for k, v in ({} if counts_only else exp[fx]['DOC']).items():
            a = act[fx]['DOC'].get(k)
            key = k.replace('DOC.', 'doc.')
            st = stats[key]; st[0] += 1
            if a == v: st[1] += 1
            elif a is not None and (norm(a) == norm(v) or (a.endswith('...<cut>') or v.endswith('...<cut>')) and norm(a)[:150] == norm(v)[:150]): st[2] += 1
            else:
                st[3] += 1; diffs[key].append((fx, '', v, (a or '<missing>')))
        for sel, ev in exp[fx]['SEL'].items():
            av = act[fx]['SEL'].get(sel)
            sel_total += 1
            if av is None: sel_issues.append((fx, sel, 'missing')); continue
            e_err, a_err = 'ERROR' in ev, 'ERROR' in av
            if e_err != a_err:
                sel_issues.append((fx, sel, 'jsoup=%s ours=%s' % ('ERROR' if e_err else 'ok(count=%s)' % ev.get('count'), 'ERROR' if a_err else 'ok(count=%s)' % av.get('count'))))
                continue
            if e_err: continue
            if ev.get('count') != av.get('count'):
                sel_issues.append((fx, sel, 'count jsoup=%s ours=%s' % (ev.get('count'), av.get('count'))))
                continue
            if not counts_only and ev.get('first') != av.get('first'):
                sel_issues.append((fx, sel, 'first jsoup=%s ours=%s' % (ev.get('first'), av.get('first'))))
            for k, v in ev.items():
                if not k.startswith('[') or counts_only: continue
                prop = k.split('.', 1)[1]
                st = stats[prop]; st[0] += 1
                a = av.get(k)
                cut = v.endswith('...<cut>') or (a or '').endswith('...<cut>')
                if a == v: st[1] += 1
                elif a is not None and (norm(a) == norm(v) or cut and norm(a)[:200] == norm(v)[:200]): st[2] += 1
                else:
                    st[3] += 1; diffs[prop].append((fx, sel, v, (a or '<missing>')))
    print('| property | compared | exact | equal after whitespace normalisation | different |')
    print('|---|---:|---:|---:|---:|')
    for k in sorted(stats):
        t, e, n, d = stats[k]
        print('| %s | %d | %d | %d | %d |' % (k, t, e, n, d))
    print('\nselector-level differences (%d of %d selector runs):' % (len(sel_issues), sel_total))
    byreason = collections.Counter()
    for fx, sel, why in sel_issues:
        byreason[(sel, why.split(' ')[0])] += 1
    for (sel, why), n in sorted(byreason.items()):
        print('  %-70s %-8s x%d' % (sel[:70], why, n))
    print('\nsamples of property differences:')
    for prop, lst in sorted(diffs.items()):
        print('\n[%s] %d differences' % (prop, len(lst)))
        seen = 0
        for fx, sel, e, a in lst:
            i = 0
            while i < min(len(e), len(a)) and e[i] == a[i]: i += 1
            lo = max(0, i - 50)
            print('  %s | %s  (first difference at char %d)\n    jsoup: ...%s\n    ours : ...%s' % (fx, sel, i, e[lo:i + 70], a[lo:i + 70]))
            seen += 1
            if seen >= samples: break
    if '--detail' in sys.argv:
        print('\nALL selector issues:')
        for x in sel_issues: print('  ', x)
main()
