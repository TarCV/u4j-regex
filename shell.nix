{
  pkgs ? import <nixpkgs> { }
}:
(pkgs.buildFHSUserEnv {
  name = "icu4j-regex-env";
  targetPkgs = pkgs: (with pkgs;
    [
      pkgs.git
      pkgs.jdk8
    ]);

  profile = ''
export JAVA_HOME="${pkgs.jdk8.home}"
export GRADLE_OPTS="-Dorg.gradle.java.home=${pkgs.jdk8.home}"
'';
}).env
