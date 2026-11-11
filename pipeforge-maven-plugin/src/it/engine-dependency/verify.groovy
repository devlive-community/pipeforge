def log = new File(basedir, 'build.log').text
assert log.contains('org.devlive.pipeforge:pipeforge-common:') && log.contains('must have provided scope; the engine supplies it')
return true
