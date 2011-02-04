/**
 * Copyright 2011 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.guvnor.server;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

import org.drools.guvnor.client.rpc.AdminArchivedPageRow;
import org.drools.guvnor.client.rpc.BuilderResult;
import org.drools.guvnor.client.rpc.BuilderResultLine;
import org.drools.guvnor.client.rpc.DetailedSerializationException;
import org.drools.guvnor.client.rpc.PageRequest;
import org.drools.guvnor.client.rpc.PageResponse;
import org.drools.guvnor.client.rpc.RuleAsset;
import org.drools.guvnor.client.rpc.TableDataResult;
import org.drools.guvnor.client.rpc.TableDataRow;
import org.drools.guvnor.server.builder.ContentPackageAssembler;
import org.drools.guvnor.server.contenthandler.ContentHandler;
import org.drools.guvnor.server.contenthandler.ContentManager;
import org.drools.guvnor.server.contenthandler.IValidating;
import org.drools.guvnor.server.security.RoleTypes;
import org.drools.guvnor.server.util.AssetFormatHelper;
import org.drools.guvnor.server.util.BuilderResultHelper;
import org.drools.guvnor.server.util.LoggingHelper;
import org.drools.guvnor.server.util.ServiceRowSizeHelper;
import org.drools.guvnor.server.util.TableDisplayHandler;
import org.drools.repository.AssetHistoryIterator;
import org.drools.repository.AssetItem;
import org.drools.repository.AssetItemIterator;
import org.drools.repository.PackageItem;
import org.drools.repository.RepositoryFilter;
import org.drools.repository.RulesRepository;
import org.jboss.seam.annotations.AutoCreate;
import org.jboss.seam.annotations.Name;

import com.google.gwt.user.client.rpc.SerializationException;

/**
 * Handles operations for Assets
 * @author Jari Timonen
 *
 */
@Name("org.drools.guvnor.server.RepositoryAssetOperations")
@AutoCreate
public class RepositoryAssetOperations {

    private RulesRepository            repository;

    private static final LoggingHelper log = LoggingHelper.getLogger( RepositoryAssetOperations.class );

    public String renameAsset(String uuid, String newName) {
        return getRepository().renameAsset( uuid, newName );
    }

    protected BuilderResult buildAsset(RuleAsset asset) throws SerializationException {
        BuilderResult result = new BuilderResult();

        try {

            ContentHandler handler = ContentManager.getHandler( asset.metaData.format );
            BuilderResultHelper builderResultHelper = new BuilderResultHelper();
            if ( asset.metaData.isBinary() ) {
                AssetItem item = getRepository().loadAssetByUUID( asset.uuid );

                handler.storeAssetContent( asset, item );

                if ( handler instanceof IValidating ) {
                    return ((IValidating) handler).validateAsset( item );
                }

                ContentPackageAssembler asm = new ContentPackageAssembler( item );
                if ( !asm.hasErrors() ) {
                    return null;
                }
                result.setLines( builderResultHelper.generateBuilderResults( asm ) );

            } else {
                if ( handler instanceof IValidating ) {
                    return ((IValidating) handler).validateAsset( asset );
                }

                PackageItem packageItem = getRepository().loadPackageByUUID( asset.metaData.packageUUID );

                ContentPackageAssembler asm = new ContentPackageAssembler( asset, packageItem );
                if ( !asm.hasErrors() ) {
                    return null;
                }
                result.setLines( builderResultHelper.generateBuilderResults( asm ) );
            }
        } catch ( Exception e ) {
            log.error( "Unable to build asset.", e );
            result = new BuilderResult();

            BuilderResultLine res = new BuilderResultLine();
            res.assetName = asset.metaData.name;
            res.assetFormat = asset.metaData.format;
            res.message = "Unable to validate this asset. (Check log for detailed messages).";
            res.uuid = asset.uuid;
            result.getLines()[0] = res;

            return result;

        }
        return result;
    }

    protected TableDataResult loadAssetHistory(final AssetItem assetItem) throws SerializationException {
        AssetHistoryIterator it = assetItem.getHistory();

        // MN Note: this uses the lazy iterator, but then loads the whole lot
        // up, and returns it.
        // The reason for this is that the GUI needs to show things in numeric
        // order by the version number.
        // When a version is restored, its previous version is NOT what you
        // thought it was - due to how JCR works
        // (its more like CVS then SVN). So to get a linear progression of
        // versions, we use the incrementing version number,
        // and load it all up and sort it. This is not ideal.
        // In future, we may do a "restore" instead just by copying content into
        // a new version, not restoring a node,
        // in which case the iterator will be in order (or you can just walk all
        // the way back).
        // So if there are performance problems with looking at lots of
        // historical versions, look at this nasty bit of code.
        List<TableDataRow> result = new ArrayList<TableDataRow>();
        while ( it.hasNext() ) {
            AssetItem historical = (AssetItem) it.next();
            long versionNumber = historical.getVersionNumber();
            if ( isHistory( assetItem, versionNumber ) ) {
                result.add( createHistoricalRow( result, historical ) );
            }
        }

        if ( result.size() == 0 ) {
            return null;
        }
        TableDataResult table = new TableDataResult();
        table.data = result.toArray( new TableDataRow[result.size()] );

        return table;
    }

    private boolean isHistory(AssetItem item, long versionNumber) {
        return versionNumber != 0 && versionNumber != item.getVersionNumber();
    }

    private TableDataRow createHistoricalRow(List<TableDataRow> result, AssetItem historical) {
        final DateFormat dateFormatter = DateFormat.getInstance();
        TableDataRow tableDataRow = new TableDataRow();
        tableDataRow.id = historical.getVersionSnapshotUUID();
        tableDataRow.values = new String[4];
        tableDataRow.values[0] = Long.toString( historical.getVersionNumber() );
        tableDataRow.values[1] = historical.getCheckinComment();
        tableDataRow.values[2] = dateFormatter.format( historical.getLastModified().getTime() );
        tableDataRow.values[3] = historical.getStateDescription();
        return tableDataRow;
    }

    protected TableDataResult loadArchivedAssets(int skip, int numRows) throws SerializationException {
        List<TableDataRow> result = new ArrayList<TableDataRow>();
        RepositoryFilter filter = new AssetItemFilter();

        AssetItemIterator it = getRepository().findArchivedAssets();
        it.skip( skip );
        int count = 0;
        while ( it.hasNext() ) {

            AssetItem archived = (AssetItem) it.next();

            if ( filter.accept( archived, "read" ) ) {
                result.add( createArchivedRow( archived ) );
                count++;
            }
            if ( count == numRows ) {
                break;
            }
        }

        return createArchivedTable( result, it );
    }

    private TableDataRow createArchivedRow(AssetItem archived) {
        TableDataRow row = new TableDataRow();
        row.id = archived.getUUID();
        row.values = new String[5];
        row.values[0] = archived.getName();
        row.values[1] = archived.getFormat();
        row.values[2] = archived.getPackageName();
        row.values[3] = archived.getLastContributor();
        row.values[4] = Long.toString( archived.getLastModified().getTime().getTime() );
        return row;
    }

    private TableDataResult createArchivedTable(List<TableDataRow> result, AssetItemIterator it) {
        TableDataResult table = new TableDataResult();
        table.data = result.toArray( new TableDataRow[result.size()] );
        table.currentPosition = it.getPosition();
        table.total = it.getSize();
        table.hasNext = it.hasNext();
        return table;
    }

    protected PageResponse<AdminArchivedPageRow> loadArchivedAssets(PageRequest request) throws SerializationException {
        // Do query
        long start = System.currentTimeMillis();
        AssetItemIterator it = getRepository().findArchivedAssets();
        log.debug( "Search time: " + (System.currentTimeMillis() - start) );

        // Populate response
        long totalRowsCount = it.getSize();
        PageResponse<AdminArchivedPageRow> response = new PageResponse<AdminArchivedPageRow>();
        List<AdminArchivedPageRow> rowList = fillAdminArchivePageRows( request, it );
        boolean bHasMoreRows = it.hasNext();
        response.setStartRowIndex( request.getStartRowIndex() );
        response.setPageRowList( rowList );
        response.setLastPage( !bHasMoreRows );
        ServiceRowSizeHelper serviceRowSizeHelper = new ServiceRowSizeHelper();
        serviceRowSizeHelper.fixTotalRowSize( request, response, totalRowsCount, rowList.size(), bHasMoreRows );

        long methodDuration = System.currentTimeMillis() - start;
        log.debug( "Searched for Archived Assests in " + methodDuration + " ms." );
        return response;
    }

    private List<AdminArchivedPageRow> fillAdminArchivePageRows(PageRequest request, AssetItemIterator it) {
        int skipped = 0;
        int pageSize = request.getPageSize();
        int startRowIndex = request.getStartRowIndex();
        RepositoryFilter filter = new AssetItemFilter();
        List<AdminArchivedPageRow> rowList = new ArrayList<AdminArchivedPageRow>( request.getPageSize() );

        while ( it.hasNext() && (pageSize < 0 || rowList.size() < pageSize) ) {
            AssetItem archivedAssetItem = (AssetItem) it.next();

            // Filter surplus assets
            if ( filter.accept( archivedAssetItem, RoleTypes.READ ) ) {

                // Cannot use AssetItemIterator.skip() as it skips non-filtered
                // assets whereas startRowIndex is the index of the
                // first displayed asset (i.e. filtered)
                if ( skipped >= startRowIndex ) {
                    rowList.add( makeAdminArchivedPageRow( archivedAssetItem ) );
                }
                skipped++;
            }
        }
        return rowList;
    }

    private AdminArchivedPageRow makeAdminArchivedPageRow(AssetItem assetItem) {
        AdminArchivedPageRow row = new AdminArchivedPageRow();
        row.setUuid( assetItem.getUUID() );
        row.setFormat( assetItem.getFormat() );
        row.setName( assetItem.getName() );
        row.setPackageName( assetItem.getPackageName() );
        row.setLastContributor( assetItem.getLastContributor() );
        row.setLastModified( assetItem.getLastModified().getTime() );
        return row;
    }
    
    protected TableDataResult listAssets(String packageUuid, String formats[], int skip, int numRows, String tableConfig) throws SerializationException {
        log.debug( "Loading asset list for [" + packageUuid + "]" );
        if ( numRows == 0 ) {
            throw new DetailedSerializationException( "Unable to return zero results (bug)", "probably have the parameters around the wrong way, sigh..." );
        }
        long start = System.currentTimeMillis();
        PackageItem pkg = getRepository().loadPackageByUUID( packageUuid );
        AssetItemIterator it;
        if ( formats.length > 0 ) {
            it = pkg.listAssetsByFormat( formats );
        } else {
            it = pkg.listAssetsNotOfFormat( AssetFormatHelper.listRegisteredTypes() );
        }
        TableDisplayHandler handler = new TableDisplayHandler( tableConfig );
        log.debug( "time for asset list load: " + (System.currentTimeMillis() - start) );
        return handler.loadRuleListTable( it, skip, numRows );
    }

    public void setRepository(RulesRepository repository) {
        this.repository = repository;
    }

    public RulesRepository getRepository() {
        return repository;
    }

}