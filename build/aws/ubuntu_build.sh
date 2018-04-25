#!/bin/ksh

function f_aptgetInstall {
	sudo apt-get -qq install $1
}

function f_ubuntu_install_java {
    echo "installing java"
    f_aptgetInstall "default-jdk" 
    typeset java7_tar=jdk-7u80-linux-x64.tar.gz
    f_downloadTar "$java7_tar" "http://ftp.osuosl.org/pub/funtoo/distfiles/oracle-java/$java7_tar"

    f_fillInBuildConfigVariable "JAVA_8_HOME" "/usr/lib/jvm/java-1.8.0-openjdk-amd64"
    f_fillInBuildConfigVariable "JAVA_7_HOME" "$LIB_ROOT/$java7"
}

function f_ubuntu_fillin_build_skfs {    
    echo "BUILD SKFS"
    f_yumInstall "fuse" #(/bin/fusermount, /etc/fuse.conf, etc.)
    f_yumInstall "fuse-devel" #(.h files, .so)
    f_fillInBuildConfigVariable "FUSE_INC"  "/usr/include/fuse"
    f_fillInBuildConfigVariable "FUSE_LIB"  "/lib64"

    f_yumInstall "zlib"
    f_yumInstall "zlib-devel"
    f_overrideBuildConfigVariable "ZLIB_INC" "/usr/include"
    f_overrideBuildConfigVariable "ZLIB_LIB" "/usr/lib64"

    f_yumInstall "valgrind"	#(not sure this is necessary)
    f_yumInstall "valgrind-devel" #(/usr/include/valgrind/valgrind.h)
    f_fillInBuildConfigVariable "VALGRIND_INC" "/usr/include"
}

cd ..
source lib/common.lib
source lib/build_sk_client.lib	# for copying kill_process_and_children.pl
cd -

source lib/common.lib

echo "BUILD"
f_aws_install_ant
f_ubuntu_install_java
f_aws_install_zk

f_generatePrivateKey

sk_repo_home=$LIB_ROOT/$REPO_NAME
f_aws_fillin_vars

echo "BUILDING JACE"
f_aptgetInstall "boost"
f_aws_install_and_symlink_boost
f_aws_install_jace

f_aptgetInstall "gcc-c++" # for g++
gpp_path=/usr/bin/g++
cd $BUILD_DIR
./$BUILD_JACE_SCRIPT_NAME $gpp_path 

f_aws_symlink_jace

f_aws_fillin_build_client "$gpp_path"
f_ubuntu_fillin_build_skfs

source $BUILD_CONFIG_FILE
f_aws_edit_configs
f_aws_skc

cd $BUILD_DIR/aws
./aws_zk.sh "start"
cd ..
./$BUILD_SCRIPT_NAME
cd aws
./aws_zk.sh "stop"
