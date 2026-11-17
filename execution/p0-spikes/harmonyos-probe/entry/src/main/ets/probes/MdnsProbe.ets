import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { mdns } from '@kit.NetworkKit';
import { ProbeLogger } from './ProbeLogger';

const AURI_GATEWAY_SERVICE_TYPE = '_auri-gateway._tcp';

/**
 * LAN mDNS discovery check for `_auri-gateway._tcp` (ADR-0018).
 * Logs service discovery lifecycle, found services, and resolved host/port details.
 */
export class MdnsProbe {
  private discoveryService: mdns.DiscoveryService | null = null;
  private searching = false;
  private onStatusChangeCallback: ((status: string) => void) | null = null;

  setOnStatusChange(callback: ((status: string) => void) | null): void {
    this.onStatusChangeCallback = callback;
  }

  isSearching(): boolean {
    return this.searching;
  }

  start(context: common.UIAbilityContext): void {
    const logger = ProbeLogger.getInstance();
    if (this.searching) {
      return;
    }

    try {
      const svc = mdns.createDiscoveryService(context, AURI_GATEWAY_SERVICE_TYPE);
      this.discoveryService = svc;

      svc.on('discoveryStart', (data: mdns.DiscoveryEventInfo) => {
        this.searching = true;
        this.emitStatus(`searching (${AURI_GATEWAY_SERVICE_TYPE})`);
        logger.log('mdns_discovery_started', {
          service_type: AURI_GATEWAY_SERVICE_TYPE,
          raw_event: JSON.stringify(data)
        });
      });

      svc.on('discoveryStop', (data: mdns.DiscoveryEventInfo) => {
        this.searching = false;
        this.emitStatus('stopped');
        logger.log('mdns_discovery_stopped', {
          service_type: AURI_GATEWAY_SERVICE_TYPE,
          raw_event: JSON.stringify(data)
        });
      });

      svc.on('serviceFound', (info: mdns.LocalServiceInfo) => {
        logger.log('mdns_service_found', {
          service_type: info.serviceType,
          service_name: info.serviceName,
          port: info.port ?? -1
        });
        this.resolveService(context, info);
      });

      svc.on('serviceLost', (info: mdns.LocalServiceInfo) => {
        logger.log('mdns_service_lost', {
          service_type: info.serviceType,
          service_name: info.serviceName
        });
      });

      svc.startSearchingMDNS();
      this.searching = true;
      this.emitStatus(`starting (${AURI_GATEWAY_SERVICE_TYPE})`);
    } catch (err) {
      const bizErr = err as BusinessError;
      this.searching = false;
      this.emitStatus(`error: ${bizErr.code}`);
      logger.log('mdns_discovery_start_failed', {
        service_type: AURI_GATEWAY_SERVICE_TYPE,
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }

  stop(): void {
    const logger = ProbeLogger.getInstance();
    if (this.discoveryService === null) {
      return;
    }
    const svc = this.discoveryService;
    this.discoveryService = null;
    this.searching = false;

    try {
      svc.stopSearchingMDNS();
      svc.off('discoveryStart');
      svc.off('discoveryStop');
      svc.off('serviceFound');
      svc.off('serviceLost');
      this.emitStatus('stopped');
    } catch (err) {
      const bizErr = err as BusinessError;
      logger.log('mdns_discovery_stop_failed', {
        code: bizErr.code,
        message: bizErr.message
      });
    }
  }

  private resolveService(context: common.UIAbilityContext, serviceInfo: mdns.LocalServiceInfo): void {
    const logger = ProbeLogger.getInstance();
    mdns.resolveLocalService(context, serviceInfo)
      .then((resolved: mdns.LocalServiceInfo) => {
        const attrKeys: string[] = [];
        if (resolved.serviceAttribute) {
          for (let i = 0; i < resolved.serviceAttribute.length; i += 1) {
            attrKeys.push(resolved.serviceAttribute[i].key);
          }
        }
        const hostAddr = resolved.host ? resolved.host.address : '';
        this.emitStatus(`resolved: ${hostAddr}:${resolved.port ?? -1}`);
        logger.log('mdns_service_resolved', {
          service_type: resolved.serviceType,
          service_name: resolved.serviceName,
          host_address: hostAddr,
          port: resolved.port ?? -1,
          attribute_keys: attrKeys
        });
      })
      .catch((err: BusinessError) => {
        this.emitStatus(`resolve_failed: ${err.code}`);
        logger.log('mdns_service_resolve_failed', {
          service_type: serviceInfo.serviceType,
          service_name: serviceInfo.serviceName,
          code: err.code,
          message: err.message
        });
      });
  }

  private emitStatus(status: string): void {
    if (this.onStatusChangeCallback !== null) {
      this.onStatusChangeCallback(status);
    }
  }
}
