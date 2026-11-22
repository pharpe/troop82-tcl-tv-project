import sys,re
x=sys.stdin.read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    g=lambda k:(re.search(k+r'="([^"]*)"',n) or [None,''])[1]
    t=g(' text') or g('content-desc')
    if t: print('F' if g('focused')=='true' else ' ', 'on ' if g('checked')=='true' else '   ', g('bounds'), t[:90])
