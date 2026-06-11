const fs = require("fs");
const path = require("path");
const webpack = require("webpack");

const nodeNetShim = path.resolve(__dirname, "node-net-empty.mjs");
fs.writeFileSync(nodeNetShim, "export default {};\n");

config.plugins = config.plugins || [];
config.plugins.push(
  new webpack.NormalModuleReplacementPlugin(/^node:net$/, nodeNetShim),
);
