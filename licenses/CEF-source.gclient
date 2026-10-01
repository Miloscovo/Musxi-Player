# Restoration configuration: source pins match CEF-source-revisions.json.
# The original verification used siso_version=latest; pin its resolved instance.
solutions = [{'managed': False, 'name': 'src',
 'url': 'https://chromium.googlesource.com/chromium/src.git@152.0.7977.83',
 'custom_vars': {'checkout_pgo_profiles': False, 'source_tarball': False,
                'siso_version': 'TupZUdv9YTinXM5vf61WKpINxNrllerRprrKorxAVqEC'},
 'deps_file': 'DEPS', 'safesync_url': ''}]
