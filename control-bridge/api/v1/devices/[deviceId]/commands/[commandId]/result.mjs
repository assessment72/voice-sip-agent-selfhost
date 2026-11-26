import { reportDeviceCommand } from "../../../../../../lib/device-commands.mjs";

export default async function handler(req, res) {
  return reportDeviceCommand(req, res);
}
