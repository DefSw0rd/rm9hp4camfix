import os, shutil, sys, zipfile


def main():
    base_apk, dex_path, extra_root, out_apk = sys.argv[1:5]
    shutil.copyfile(base_apk, out_apk)
    added = []
    with zipfile.ZipFile(out_apk, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex_path, 'classes.dex')
        added.append('classes.dex')
        for base, _, files in os.walk(extra_root):
            for name in files:
                full = os.path.join(base, name)
                rel = os.path.relpath(full, extra_root).replace('\\', '/')
                if rel.startswith('META-INF/'):
                    z.write(full, rel)
                    added.append(rel)
    print('packed:', ', '.join(added))


if __name__ == '__main__':
    main()
