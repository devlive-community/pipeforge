def log = new File(basedir, 'build.log').text
assert log.contains('version must be a semantic version such as 1.0.0: one')
assert log.contains('api-version must name an API major version such as "1.x": zero')
return true
