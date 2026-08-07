// Lets the example bundle the library straight from ../src instead of a published copy,
// so edits to the module show up on the next reload.
const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const moduleRoot = path.resolve(projectRoot, '..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [moduleRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(moduleRoot, 'node_modules'),
];
config.resolver.disableHierarchicalLookup = true;

module.exports = config;
