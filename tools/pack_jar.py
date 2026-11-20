import os, sys, zipfile

root, out = sys.argv[1], sys.argv[2]
n = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class'):
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, root))
                n += 1
print('packed', n, 'classes ->', out)
