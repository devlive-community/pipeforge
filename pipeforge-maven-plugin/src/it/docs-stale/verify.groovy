def log = new File(basedir, 'build.log').text
assert log.contains('greeting.json is out of date: line 4 differs') : 'stale docs not reported'
assert log.contains('without -Dpipeforge.docs.check to update it') : 'no hint how to update'
assert new File(basedir, 'docs/greeting.json').text.contains('An old name') : 'the check must not write'
return true
