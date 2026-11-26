import { pollDeviceCommands } from "../../../../../lib/device-commands.mjs";

export default async function handler(req, res) {
  return pollDeviceCommands(req, res);
}
