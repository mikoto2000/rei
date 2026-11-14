# glib 0.18.5 security backport

Source: [crates.io glib 0.18.5](https://crates.io/crates/glib/0.18.5).
The upstream archive SHA-256 is
`233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`.
The package includes the upstream MIT license and copyright notice.

The two-line change in `src/variant_iter.rs` is the upstream
[VariantStrIter fix](https://github.com/gtk-rs/gtk-rs-core/commit/b5a4071e439bef2b5eea76c3aa25e5ae84839e34)
for [RUSTSEC-2024-0429](https://rustsec.org/advisories/RUSTSEC-2024-0429.html).
Tauri's Linux GTK3 and WebKitGTK dependencies require glib 0.18. Remove this
backport when the Linux dependency graph can resolve a published fixed version.
