import Foundation
import Combine

enum CreateGroupStatus: Equatable {
    case idle
    case creating
    case success(groupId: String)
    case error(message: String)
}

@MainActor
final class GroupViewModel: ObservableObject {
    @Published var groups: [ChatGroup] = []
    @Published var selectedMembers: [String] = []
    @Published var availableContacts: [Contact] = []
    @Published var createStatus: CreateGroupStatus = .idle

    private let repo = FlareRepository.shared
    private var cancellables = Set<AnyCancellable>()

    init() {
        repo.$contacts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] contacts in
                self?.availableContacts = contacts
            }
            .store(in: &cancellables)

        refreshGroups()
    }

    func refreshGroups() {
        do {
            let ffiGroups = try repo.listGroups()
            groups = ffiGroups.map { ffi in
                let memberCount = (try? repo.getGroupMembers(groupId: ffi.groupId).count) ?? 0
                return ChatGroup(
                    groupId: ffi.groupId,
                    groupName: ffi.groupName,
                    createdAt: ffi.createdAt,
                    creatorDeviceId: ffi.creatorDeviceId,
                    memberCount: memberCount
                )
            }
        } catch {
            groups = []
        }
    }

    func createGroup(groupName: String) {
        createStatus = .creating
        let groupId = UUID().uuidString

        do {
            try repo.createGroup(groupId: groupId, groupName: groupName)

            for deviceId in selectedMembers {
                try repo.addGroupMember(groupId: groupId, deviceId: deviceId)
            }

            createStatus = .success(groupId: groupId)
            clearSelection()
            refreshGroups()
        } catch {
            createStatus = .error(message: error.localizedDescription)
        }
    }

    func toggleMemberSelection(deviceId: String) {
        if let index = selectedMembers.firstIndex(of: deviceId) {
            selectedMembers.remove(at: index)
        } else {
            selectedMembers.append(deviceId)
        }
    }

    func clearSelection() {
        selectedMembers = []
    }

    func removeGroupMember(groupId: String, deviceId: String) {
        do {
            try repo.removeGroupMember(groupId: groupId, deviceId: deviceId)
            refreshGroups()
        } catch {
            // Silently fail; group list remains unchanged
        }
    }
}
