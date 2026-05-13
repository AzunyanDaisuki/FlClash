import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

class OnDemandView extends ConsumerStatefulWidget {
  const OnDemandView({super.key});

  @override
  ConsumerState createState() => _OnDemandViewState();
}

class _OnDemandViewState extends ConsumerState<OnDemandView> {
  Future<void> _handleRequestLocationPermission() async {
    final permission = ref.read(locationPermissionsProvider);
    if (permission == WifiSsidPermission.granted) {
      return;
    }
    if (permission == WifiSsidPermission.permanentlyDenied) {
      if (system.isMacOS) {
        final appLocalizations = context.appLocalizations;
        globalState.showMessage(
          title: appLocalizations.locationPermissionRequired,
          cancelable: false,
          message: TextSpan(
            style: context.textTheme.bodyMedium,
            text: appLocalizations.locationPermissionGuide(appName),
          ),
        );
      }
      if (system.isAndroid) {
        app?.openAppSettings();
      }
    } else {
      globalState.container.read(locationPermissionsProvider.notifier).value =
          await wifiSsidManager.requestPermission();
    }
  }

  void _handleOpenBatteryOptimizationSettings() {
    app?.openBatteryOptimizationSettings();
  }

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    final batteryOptimizationDisable = ref.watch(
      batteryOptimizationDisableProvider,
    );
    final locationPermissionsGranted = ref.watch(
      locationPermissionsProvider.select(
        (state) => state == WifiSsidPermission.granted,
      ),
    );
    return CommonScaffold(
      body: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: SliverToBoxAdapter(
              child: generateSectionV3(
                title: appLocalizations.prerequisites,
                items: [
                  if (system.isAndroid)
                    DecorationListItem(
                      minVerticalPadding: 8,
                      title: Text(appLocalizations.ignoreBatteryOptimization),
                      subtitle: Text(appLocalizations.batteryOptimizationDesc),
                      trailing: CommonMinFilledButtonTheme(
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: batteryOptimizationDisable
                                ? null
                                : context.colorScheme.error,
                            padding: const EdgeInsets.symmetric(horizontal: 12),
                          ),
                          onPressed: _handleOpenBatteryOptimizationSettings,
                          child: Text(
                            batteryOptimizationDisable ? '已授权' : '点击授权',
                          ),
                        ),
                      ),
                    ),
                  if (system.isAndroid || system.isMacOS)
                    DecorationListItem(
                      minVerticalPadding: 8,
                      title: Text(appLocalizations.locationPermission),
                      subtitle: Text(appLocalizations.locationPermissionDesc),
                      trailing: CommonMinFilledButtonTheme(
                        child: FilledButton(
                          style: FilledButton.styleFrom(
                            backgroundColor: locationPermissionsGranted
                                ? null
                                : context.colorScheme.error,
                            padding: const EdgeInsets.symmetric(horizontal: 12),
                          ),
                          onPressed: _handleRequestLocationPermission,
                          child: Text(
                            locationPermissionsGranted ? '已授权' : '点击授权',
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: SliverToBoxAdapter(
              child: ListHeader(
                title: appLocalizations.excludeSsids,
                subTitle: appLocalizations.excludeSsidsDesc,
                actions: [
                  CommonMinFilledButtonTheme(
                    child: FilledButton.tonal(
                      onPressed: () {},
                      child: Text(appLocalizations.add),
                    ),
                  ),
                ],
              ),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(
              horizontal: 16,
            ).copyWith(top: 12),
            sliver: SliverToBoxAdapter(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 0,
                  vertical: 48,
                ),
                // type: CommonCardType.filled,
                child: NullStatus(label: appLocalizations.ssidsEmpty),
              ),
            ),
          ),
        ],
      ),
      title: appLocalizations.onDemand,
    );
  }
}
