let pkgs = import <nixpkgs> { config = { allowUnfree = true; android_sdk.accept_license = true; }; };
in (pkgs.androidenv.composeAndroidPackages { platformVersions = [ "36" ]; buildToolsVersions = [ "36.0.0" ]; includeEmulator = false; includeSystemImages = false; includeNDK = false; }).androidsdk
