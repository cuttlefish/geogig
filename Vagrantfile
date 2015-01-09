# -*- mode: ruby -*-
# vi: set ft=ruby :

### Instructions ################
# vagrant up {ubuntu | centos}  #
# vagrant ssh {ubuntu | centos} #
# cd /vagrant/src/parent        #
# mvn clean install             #
#################################

VAGRANTFILE_API_VERSION = "2"

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.box = ""

  config.ssh.forward_agent = true

  config.vm.provider "virtualbox" do |vb|
    #vb.gui = true # this should generally be disabled
    vb.memory = 2048
    vb.cpus = 2
  end

  config.vm.define "centos" do |centos|
    centos.vm.box = "puppetlabs/centos-6.5-64-nocm"

    script = <<SCRIPT
      echo I am provisioning...
      date > /etc/vagrant_provisioned_at
      yum update -y
      /etc/init.d/vboxadd setup

      ### Install Java
      #yum install -y wget && wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u5-b13/jdk-8u5-linux-x64.rpm
      #rpm -ivh jdk-8u5-linux-x64.rpm && rm jdk-8u5-linux-x64.rpm
      yum install -y wget && wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/7u71-b14/jdk-7u71-linux-x64.rpm
      rpm -ivh jdk-7u71-linux-x64.rpm && rm jdk-7u71-linux-x64.rpm

      ### Install Maven
      TEMPORARY_DIRECTORY="$(mktemp -d)"
      DOWNLOAD_TO="$TEMPORARY_DIRECTORY/maven.tgz"

      echo 'Downloading Maven to: ' "$DOWNLOAD_TO"

      wget -O "$DOWNLOAD_TO" http://apache.mirror.gtcomm.net/maven/maven-3/3.2.5/binaries/apache-maven-3.2.5-bin.tar.gz

      echo 'Extracting Maven'
      tar xzf $DOWNLOAD_TO -C $TEMPORARY_DIRECTORY
      rm $DOWNLOAD_TO

      echo 'Configuring Envrionment'

      mv $TEMPORARY_DIRECTORY/apache-maven-* /usr/local/maven
      echo -e 'export M2_HOME=/usr/local/maven\nexport PATH=${M2_HOME}/bin:${PATH}' > /etc/profile.d/maven.sh
      source /etc/profile.d/maven.sh

      echo 'The maven version: ' `mvn -version` ' has been installed.'
      echo 'Removing the temporary directory...'
      rm -r "$TEMPORARY_DIRECTORY"
      echo 'Your Maven Installation is Complete.'

      ### Other dependencies
      # Use the postgres RPM repo
      rpm -ivh http://yum.postgresql.org/9.4/redhat/rhel-6-x86_64/pgdg-centos94-9.4-1.noarch.rpm
      yum install -y proj-devel
      yum install -y geos-devel

      ### Utils
      yum install -y git
SCRIPT
    centos.vm.provision "shell", inline: script
  end

  config.vm.define "ubuntu" do |ubuntu|
    ubuntu.vm.box = "ubuntu/trusty64"

    script = <<SCRIPT
      echo I am provisioning...
      date > /etc/vagrant_provisioned_at
      apt-get update
      apt-get upgrade -y
      /etc/init.d/vboxadd setup
      wget --no-check-certificate https://github.com/aglover/ubuntu-equip/raw/master/equip_java7_64.sh && bash equip_java7_64.sh
      apt-get install -y maven

      # Other dependencies
      apt-get install -y libgeos-dev
      apt-get install -y libproj0
SCRIPT
    ubuntu.vm.provision "shell", inline: script
  end
end
