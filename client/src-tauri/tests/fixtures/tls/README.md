# TLS regression fixtures

`ca.der` signs `server.der`, whose only DNS subject alternative name is
`localhost`. `server-key.der` is its unencrypted PKCS#8 EC P-256 test key.
These are public test fixtures, generated with OpenSSL for local handshakes;
the CA is never installed in the OS trust store. The certificates expire in 2046.

The tests check rejection of this private CA by the production Mozilla bundle,
acceptance with an explicitly selected fixture CA, and rejection of a hostname
mismatch even when the certificate chain is trusted.
