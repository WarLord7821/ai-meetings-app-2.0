/**
 * Production environment — points at the Oracle Cloud Always Free VM.
 * Replace the host with the VM's public IP (or a domain) once provisioned.
 */
export const environment = {
  production: true,
  apiBaseUrl: 'http://<YOUR_ORACLE_VM_IP_OR_DOMAIN>:8080',
};