package com.bloodnet.inventory;

import com.bloodnet.inventory.internal.BloodBank;
import com.bloodnet.inventory.internal.BloodBankRepository;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Public API: look up blood banks. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankDirectory {

    private final BloodBankRepository banks;

    public List<BankInfo> all() {
        return banks.findAll().stream().map(BankDirectory::toInfo).toList();
    }

    public BankInfo get(Long id) {
        return banks.findById(id).map(BankDirectory::toInfo).orElseThrow(() -> new NotFoundException("Blood bank", id));
    }

    public boolean exists(Long id) {
        return id != null && banks.existsById(id);
    }

    public Optional<Long> findIdByCode(String code) {
        return banks.findByCode(code).map(BloodBank::getId);
    }

    static BankInfo toInfo(BloodBank b) {
        return new BankInfo(b.getId(), b.getCode(), b.getName(), b.getCity(), b.getAddress(), b.getPhone(),
                b.getLatitude(), b.getLongitude());
    }
}
