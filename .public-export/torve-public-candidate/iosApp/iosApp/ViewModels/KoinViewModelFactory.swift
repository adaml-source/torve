import Foundation
import shared

/// Typed Koin resolution helper for ViewModels.
enum KoinViewModelFactory {

    private static var koin: Koin_coreKoin {
        KoinHelper.shared.getKoin()
    }

    static func resolve<T: AnyObject>(_ type: T.Type) -> T {
        guard let instance = koin.get(objCClass: type) as? T else {
            fatalError("Koin could not resolve \(type)")
        }
        return instance
    }

    // MARK: - Convenience accessors

    static func homeViewModel() -> HomeViewModel {
        resolve(HomeViewModel.self)
    }

    static func settingsViewModel() -> SettingsViewModel {
        resolve(SettingsViewModel.self)
    }

    static func channelsViewModel() -> ChannelsViewModel {
        resolve(ChannelsViewModel.self)
    }

    static func detailViewModel() -> DetailViewModel {
        resolve(DetailViewModel.self)
    }

    static func searchViewModel() -> SearchViewModel {
        resolve(SearchViewModel.self)
    }

    static func watchlistViewModel() -> WatchlistViewModel {
        resolve(WatchlistViewModel.self)
    }

    static func downloadViewModel() -> DownloadViewModel {
        resolve(DownloadViewModel.self)
    }

    static func calendarViewModel() -> CalendarViewModel {
        resolve(CalendarViewModel.self)
    }

    static func subscriptionViewModel() -> SubscriptionViewModel {
        resolve(SubscriptionViewModel.self)
    }

    static func addonViewModel() -> AddonViewModel {
        resolve(AddonViewModel.self)
    }

    static func setupWizardViewModel() -> SetupWizardViewModel {
        resolve(SetupWizardViewModel.self)
    }

    static func setupWizardCoordinator() -> SetupWizardCoordinator {
        resolve(SetupWizardCoordinator.self)
    }

    static func discoverViewModel() -> DiscoverViewModel {
        resolve(DiscoverViewModel.self)
    }

    static func moodMatcherViewModel() -> MoodMatcherViewModel {
        resolve(MoodMatcherViewModel.self)
    }

    static func statsViewModel() -> StatsViewModel {
        resolve(StatsViewModel.self)
    }

    static func profileViewModel() -> ProfileViewModel {
        resolve(ProfileViewModel.self)
    }

    static func personViewModel() -> PersonViewModel {
        resolve(PersonViewModel.self)
    }

    static func mdbListViewModel() -> MdbListViewModel {
        resolve(MdbListViewModel.self)
    }

    static func seeAllViewModel() -> SeeAllViewModel {
        resolve(SeeAllViewModel.self)
    }

    static func downloadCatalogueViewModel() -> DownloadCatalogueViewModel {
        resolve(DownloadCatalogueViewModel.self)
    }

    static func deviceGovernanceViewModel() -> DeviceGovernanceViewModel {
        resolve(DeviceGovernanceViewModel.self)
    }

    static func pandaSetupViewModel() -> PandaSetupViewModel {
        resolve(PandaSetupViewModel.self)
    }

    static func nzbdavSetupViewModel() -> NzbdavSetupViewModel {
        resolve(NzbdavSetupViewModel.self)
    }

    static func secretsTransferSenderViewModel() -> SecretsTransferSenderViewModel {
        resolve(SecretsTransferSenderViewModel.self)
    }

    static func secretsTransferReceiverViewModel() -> SecretsTransferReceiverViewModel {
        resolve(SecretsTransferReceiverViewModel.self)
    }
}
