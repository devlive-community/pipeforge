def log = new File(basedir, 'build.log').text
assert log.contains("GreetingSourceFactory.identifier() returns 'hello' but plugin.yaml declares 'greeting'")
return true
