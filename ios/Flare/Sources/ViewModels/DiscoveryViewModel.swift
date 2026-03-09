import Foundation
import Contacts

enum SearchState: Equatable {
    case idle
    case searching(mode: DiscoveryMode, hint: String)
    case found(DeviceIdentity, method: String)
    case error(String)
}

enum DiscoveryMode: Equatable {
    case sharedPhrase
    case phoneNumber
    case contactImport
}

@MainActor
final class DiscoveryViewModel: ObservableObject {
    @Published var searchState: SearchState = .idle
    @Published var importedCount: Int = 0

    private let repo = FlareRepository.shared

    func startPhraseSearch(_ phrase: String) {
        guard !phrase.isEmpty else { return }
        searchState = .searching(mode: .sharedPhrase, hint: phrase)

        Task {
            do {
                _ = try repo.startPassphraseSearch(phrase)
            } catch {
                searchState = .error(error.localizedDescription)
            }
        }
    }

    func startPhoneSearch(myPhone: String, theirPhone: String) {
        guard !myPhone.isEmpty, !theirPhone.isEmpty else { return }
        searchState = .searching(mode: .phoneNumber, hint: theirPhone)

        Task {
            do {
                _ = try repo.startPhoneSearch(myPhone: myPhone, theirPhone: theirPhone)
            } catch {
                searchState = .error(error.localizedDescription)
            }
        }
    }

    func importContacts(myPhone: String) {
        searchState = .searching(mode: .contactImport, hint: "Importing contacts…")

        Task {
            do {
                let store = CNContactStore()
                try await store.requestAccess(for: .contacts)

                let keys = [CNContactPhoneNumbersKey] as [CNKeyDescriptor]
                let request = CNContactFetchRequest(keysToFetch: keys)

                var phoneNumbers: [String] = []
                try store.enumerateContacts(with: request) { contact, _ in
                    for phone in contact.phoneNumbers {
                        let number = phone.value.stringValue
                            .replacingOccurrences(of: " ", with: "")
                            .replacingOccurrences(of: "-", with: "")
                            .replacingOccurrences(of: "(", with: "")
                            .replacingOccurrences(of: ")", with: "")
                        if number.count >= 7 {
                            phoneNumbers.append(number)
                        }
                    }
                }

                let count = try repo.importPhoneContacts(myPhone: myPhone, contacts: phoneNumbers)
                importedCount = Int(count)
                searchState = .idle
            } catch {
                searchState = .error(error.localizedDescription)
            }
        }
    }

    func onContactDiscovered(deviceId: String, signingKey: Data, agreementKey: Data, method: String) {
        let identity = DeviceIdentity(
            deviceId: deviceId,
            signingPublicKey: signingKey,
            agreementPublicKey: agreementKey
        )
        searchState = .found(identity, method: method)
    }

    func cancelSearch() {
        searchState = .idle
    }

    func clearDiscovery() {
        searchState = .idle
        importedCount = 0
    }
}
