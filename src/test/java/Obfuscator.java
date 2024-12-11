import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;

record BankRecords(Collection<Owner> owners, Collection<Account> accounts, Collection<RegisterEntry> registerEntries) { }

public class Obfuscator {
    private static Logger logger = LogManager.getLogger(Obfuscator.class.getName());

    public BankRecords obfuscate(BankRecords rawObjects) {
        // Obfuscate owners
        List<Owner> newOwners = new ArrayList<>();
        for (Owner o : rawObjects.owners()) {
            String newName = o.name().charAt(0) + "."; // Only first letter
            long newId = (o.id() * 13L) % 1000000007L; 

            Random random = new Random();
            Date originalDob = o.dob(); 
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(originalDob);
            int randomDays = random.nextInt(730) - 365; // Range: -365 to +365
            calendar.add(Calendar.DAY_OF_YEAR, randomDays);
            Date newDob = calendar.getTime();

            String newSsn = "***-**-" + o.ssn().substring(o.ssn().length() - 4); // Last 4 digits
            String newZip = o.zip().substring(0, 3) + "XX"; // Only first 3 digits with "XX"
    
            newOwners.add(new Owner(newName, newId, newDob, newSsn, o.address(), o.address2(), o.city(), o.state(), newZip));
        }
        Collection<Owner> obfuscatedOwners = newOwners;
    
        // Obfuscate accounts
        List<Account> newAccounts = new ArrayList<>();
        for (Account a : rawObjects.accounts()) {
            try {
                String newName = a.getName().charAt(0) + ".";
                long newId = (a.getId() * 5L) % 1000000007L; 
                long newOwnerId = (a.getOwnerId() * 8L) % 1000000007L;
                if (a instanceof CheckingAccount) {
                    newAccounts.add(new CheckingAccount(newName, newId, a.getBalance(), 0, newOwnerId));
                } else if (a instanceof SavingsAccount) {
                    newAccounts.add(new SavingsAccount(newName, newId, a.getBalance(), 0, newOwnerId));
                } else {
                    logger.warn("Unknown account type: " + a.getClass().getName());
                }
            } catch (Exception e) {
                logger.error("Error encrypting account number: ", e);
            }
        }
        Collection<Account> obfuscatedAccounts = newAccounts;
    
        // Obfuscate register entries
        List<RegisterEntry> newRegisterEntries = new ArrayList<>();
        for (RegisterEntry r : rawObjects.registerEntries()) {
            try {
                long newId = (r.id() * 21L) % 1000000007L; 
                long newAccountId = (r.id() * 34L) % 1000000007L; 
                
                double newAmount = Math.round(r.amount() * 100 + new Random().nextInt(200) - 100) / 100.0;

                Random random = new Random();
                Date originalDate = r.date(); 
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(originalDate);
                int randomDays = random.nextInt(730) - 365; // Range: -365 to +365
                calendar.add(Calendar.DAY_OF_YEAR, randomDays);
                Date newDate = calendar.getTime();

                newRegisterEntries.add(new RegisterEntry(newId, newAccountId, r.entryName(), newAmount, newDate));
            } catch (Exception e) {
                logger.error("Error encrypting transaction ID: ", e);
            }
        }
        Collection<RegisterEntry> obfuscatedRegisterEntries = newRegisterEntries;
    
        // Create and return the new BankRecords object
        return new BankRecords(obfuscatedOwners, obfuscatedAccounts, obfuscatedRegisterEntries);
    }
    
    // Example encrypt method placeholder
    private String encrypt(String input) throws Exception {
        // Implement encryption logic here
        return "encrypted_" + input; // Example stub
    }
    
    

    /**
     * Change the integration test suite to point to our obfuscated production
     * records.
     *
     * To use the original integration test suite files run
     *   "git checkout -- src/test/resources/persister_integ.properties"
     */
    public void updateIntegProperties() throws IOException {
        Properties props = new Properties();
        File propsFile = new File("src/test/resources/persister_integ.properties".replace('/', File.separatorChar));
        if (! propsFile.exists() || !propsFile.canWrite()) {
            throw new RuntimeException("Properties file must exist and be writable: " + propsFile);
        }
        try (InputStream propsStream = new FileInputStream(propsFile)) {
            props.load(propsStream);
        }
        props.setProperty("persisted.suffix", "_prod");
        logger.info("Updating properties file '{}'", propsFile);
        try (OutputStream propsStream = new FileOutputStream(propsFile)) {
            String comment = String.format(
                    "Note: Don't check in changes to this file!!\n" +
                    "#Modified by %s\n" +
                    "#to reset run 'git checkout -- %s'",
                    this.getClass().getName(), propsFile);
            props.store(propsStream, comment);
        }
    }

    public static void main(String[] args) throws Exception {
        // enable assertions
        Obfuscator.class.getClassLoader().setClassAssertionStatus("Obfuscator", true);
        logger.info("Loading Production Records");
        Persister.setPersisterPropertiesFile("persister_prod.properties");
        Bank bank = new Bank();
        bank.loadAllRecords();

        logger.info("Obfuscating records");
        Obfuscator obfuscator = new Obfuscator();
        // Make a copy of original values so we can compare length
        // deep-copy collections so changes in obfuscator don't impact originals
        BankRecords originalRecords = new BankRecords(
                new ArrayList<>(bank.getAllOwners()),
                new ArrayList<>(bank.getAllAccounts()),
                new ArrayList<>(bank.getAllRegisterEntries()));
        BankRecords obfuscatedRecords = obfuscator.obfuscate(originalRecords);

        logger.info("Saving obfuscated records");
        obfuscator.updateIntegProperties();
        Persister.resetPersistedFileNameAndDir();
        Persister.setPersisterPropertiesFile("persister_integ.properties");
        // old version of file is cached so we need to override prefix (b/c file changed
        // is not the one on classpath)
        Persister.setPersistedFileSuffix("_prod");
        // writeReords is cribbed from Bank.saveALlRecords(), refactor into common
        // method?
        Persister.writeRecordsToCsv(obfuscatedRecords.owners(), "owners");
        Map<Class<? extends Account>, List<Account>> splitAccounts = obfuscatedRecords
                .accounts()
                .stream()
                .collect(Collectors.groupingBy(rec -> rec.getClass()));
        Persister.writeRecordsToCsv(splitAccounts.get(SavingsAccount.class), "savings");
        Persister.writeRecordsToCsv(splitAccounts.get(CheckingAccount.class),"checking");
        Persister.writeRecordsToCsv(obfuscatedRecords.registerEntries(), "register");

        logger.info("Original   record counts: {} owners, {} accounts, {} registers",
                originalRecords.owners().size(),
                originalRecords.accounts().size(),
                originalRecords.registerEntries().size());
        logger.info("Obfuscated record counts: {} owners, {} accounts, {} registers",
                obfuscatedRecords.owners().size(),
                obfuscatedRecords.accounts().size(),
                obfuscatedRecords.registerEntries().size());

        if (obfuscatedRecords.owners().size() != originalRecords.owners().size())
            throw new AssertionError("Owners count mismatch");
        if (obfuscatedRecords.accounts().size() != originalRecords.accounts().size())
            throw new AssertionError("Account count mismatch");
        if (obfuscatedRecords.registerEntries().size() != originalRecords.registerEntries().size())
            throw new AssertionError("RegisterEntries count mismatch");
    }
}
