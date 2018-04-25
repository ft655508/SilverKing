#!/bin/ksh

function f_yumInstall {
	sudo yum -y install $1
}

function f_aws_install_java {
    echo "installing java"
    typeset java8=java-1.8.0
    typeset java7=java-1.7.0
    f_yumInstall "$java8-openjdk-devel.x86_64" # you don't want java-1.8.0-openjdk.x86_64! It really only has the jre's
    f_yumInstall "$java7-openjdk-devel.x86_64" 
    f_fillInBuildConfigVariable "JAVA_8_HOME" "/usr/lib/jvm/$java8"
    f_fillInBuildConfigVariable "JAVA_7_HOME" "/usr/lib/jvm/$java7"
}

function f_aws_fillin_build_skfs {    
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
f_aws_install_java
f_aws_install_zk

f_generatePrivateKey

sk_repo_home=$LIB_ROOT/$REPO_NAME
f_aws_fillin_vars

echo "BUILDING JACE"
f_yumInstall "boost"
f_aws_install_and_symlink_boost
f_aws_install_jace

f_yumInstall "gcc-c++" # for g++
gpp_path=/usr/bin/g++
cd $BUILD_DIR
./$BUILD_JACE_SCRIPT_NAME $gpp_path 

f_aws_symlink_jace

f_aws_fillin_build_client "$gpp_path"
f_aws_fillin_build_skfs

source $BUILD_CONFIG_FILE
f_aws_edit_configs
f_aws_skc

cd $BUILD_DIR/aws
./aws_zk.sh "start"
cd ..
./$BUILD_SCRIPT_NAME
cd aws
./aws_zk.sh "stop"
