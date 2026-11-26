#!/bin/bash
# Builds the app in registry.gitlab.com/fdroid/fdroidserver:buildserver the way
# F-Droid's own CI does. The F-Droid Build workflow starts it as root, with an
# fdroiddata checkout holding this app's metadata at /build and fdroidserver at
# /opt/fdroidserver.
set -eux

if [ "$(id -u)" -eq 0 ]; then
    # Give the checkout back to the runner afterwards, even when the build fails.
    trap 'chown -R "$HOST_OWNER" /build' EXIT

    # gradlew-fdroid is only as new as the image. F-Droid's CI updates it the
    # same way before building.
    git -C /home/vagrant/gradlew-fdroid pull

    # The image is set up for its vagrant user: gradlew-fdroid sits in
    # /home/vagrant, and only the vagrant group may install the newer SDK
    # platforms and build-tools that Gradle downloads.
    chown -R vagrant:vagrant /build
    sudo --user=vagrant --set-home \
        --preserve-env=FDROID_APP_ID,FDROID_VERSION_CODE \
        bash "$0"
    exit
fi

. /etc/profile
export PATH="/opt/fdroidserver:$PATH"
export PYTHONPATH=/opt/fdroidserver
JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 >/dev/null | sed -n 's/^.*java.home = //p' | tr -d ' ')"
export JAVA_HOME

cd /build
python3 -m fdroidserver readmeta
python3 -m fdroidserver lint "$FDROID_APP_ID"
# Without --stop, fdroid build logs a failed build and still exits with 0.
python3 -m fdroidserver build --stop --test --verbose "$FDROID_APP_ID:$FDROID_VERSION_CODE"
test -f "tmp/${FDROID_APP_ID}_${FDROID_VERSION_CODE}.apk"
