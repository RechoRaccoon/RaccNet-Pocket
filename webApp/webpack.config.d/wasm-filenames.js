// The Kotlin/Wasm loader fetches its binary by literal filename
// ("./RaccNetPocketKMP-webApp.wasm"). Webpack's default content-hashed
// asset names break that lookup, so keep original asset filenames.
config.output.assetModuleFilename = "[name][ext]";
